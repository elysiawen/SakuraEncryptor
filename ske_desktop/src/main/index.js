import { app, BrowserWindow, Menu, shell, webContents } from 'electron'
import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { MediaRegistry } from './media-router.js'
import { startDavServer, startServer } from './server.js'
import { sealSecret, Storage, unsealSecret } from './storage.js'
import { Security } from './security.js'
import { CloudService } from './cloud.js'
import { ThumbDiskCache } from './thumb-cache.js'
import { LocalLibrary } from './local-fs.js'
import { UploadQueue } from './uploads.js'
import { TransferHistory } from './transfer-history.js'
import { PendingUploads } from './pending-uploads.js'
import { JobQueue } from './jobs.js'
import { DownloadQueue } from './downloads.js'
import { listPlayers } from './external-player.js'
import { createTray } from './tray.js'
import { registerIpc } from './ipc.js'
import { applyLanguage, t } from './i18n.js'
import { EmbeddedService } from './embedded/index.js'
import { WebdavGateway } from './webdav.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(here, '../..')
const preloadPath = path.join(rootDir, 'src/preload/index.cjs')
const rendererDir = path.join(rootDir, 'out/renderer')

const isDev = process.env.SKE_DEV === '1'
const devUrl = process.env.SKE_DEV_URL || 'http://localhost:5173'


if (process.platform === 'win32') app.setAppUserModelId('com.sakura.encryptor')

const registry = new MediaRegistry()
const storage = new Storage()
const security = new Security()

// Previews survive restarts, but only as AES-GCM blobs keyed by the media
// password: the directory alone reveals nothing. The setting is read lazily so
// a size change applies without recreating the service.
const thumbCache = new ThumbDiskCache({
  dir: path.join(app.getPath('userData'), 'thumb-cache'),
  settings: () => storage.settings?.cache || {},
})

// Index what a previous run left behind: usage in the settings page is then
// right before the first tile is requested, and an oversized directory is
// trimmed at launch rather than after the next folder is opened.
thumbCache.load().catch(() => {})

const cloud = new CloudService({ thumbCache })
const localLibrary = new LocalLibrary()

/**
 * The libraries as a WebDAV folder, so Explorer / Finder / Nautilus can mount
 * them. Mounting happens per library and per request; the gateway holds no
 * secret of its own beyond the credentials it mints at launch.
 */
const webdav = new WebdavGateway({
  registry,
  localLibrary,
  cloud,
  // Shares bound to a cloud profile log in for themselves, so the gateway needs
  // the vault to read that profile's secrets.
  storage,
  // A mounted folder is a second door into the same vault, so it has to close
  // the moment the launch lock goes up.
  isLocked: () => security.isEnabled() && !security.unlocked,
})

/** The dedicated WebDAV listener, once it is up. */
let davServer = null

/**
 * The shares as the settings file keeps them, with the sealed tokens unsealed.
 *
 * A build before accounts wrote a single `token`; that becomes the one mixed
 * share, which is exactly what it used to be. A token the user never chose is
 * generated once and sealed with the OS keychain, like a profile password.
 */
function davShares() {
  const saved = Array.isArray(storage.settings.webdav?.accounts) ? storage.settings.webdav.accounts : []
  const shares = saved
    .map((account) => ({
      ...account,
      token: unsealSecret(account.token),
      password: unsealSecret(account.password),
    }))
    .filter((account) => account.id && account.token)
  if (shares.length) return shares

  // Nothing usable: migrate the single token a pre-accounts build wrote, or
  // mint one. A keychain that cannot unseal counts as "nothing usable" too —
  // a share with an empty token would be unreachable anyway.
  const legacy = unsealSecret(storage.settings.webdav?.token)
  return [
    {
      id: 'main',
      name: t('settings.dav.defaultShare'),
      token: legacy || crypto.randomBytes(16).toString('hex'),
      kind: 'mixed',
    },
  ]
}

/** The live shares in the shape settings keeps: secrets sealed, never in clear. */
function storedShares() {
  return webdav.list().map((account) => ({
    id: account.id,
    name: account.name,
    username: account.username,
    kind: account.kind,
    profileId: account.profileId,
    base: account.base,
    token: sealSecret(account.token),
    // Sealed like the token: a mapped drive must keep working after a restart,
    // which means the credentials have to outlive the process.
    password: sealSecret(account.password),
  }))
}

/**
 * Start (or restart) the WebDAV listener on the persisted port.
 *
 * The port, the tokens and the credentials are settings, not per-launch values:
 * a mapped network drive survives a restart only if all three stay put.
 */
async function startDav() {
  webdav.configure(davShares())

  const wanted = Number(storage.settings.webdav?.port) || 0
  const running = await startDavServer({ webdav, port: wanted })
  davServer = running

  const patch = { accounts: storedShares() }
  // `0` on the first launch means "whatever was free": record it so the next
  // launch binds the same one. A conflict reports the temporary port but keeps
  // the user's choice, so it is tried again next time.
  if (!running.conflict && Number(storage.settings.webdav?.port) !== running.port) {
    patch.port = running.port
  }
  // Kept in step with the first share so a downgrade still finds its token.
  patch.token = sealSecret(webdav.list()[0]?.token || '')
  await storage.patchSettings({ webdav: patch })

  console.log(`[ske] webdav origin: ${running.origin} (${webdav.list().length} share(s))`)
  return running
}

/**
 * Stop the listener and let go of the files it had open.
 *
 * The shares themselves stay configured: switching mounting back on restores
 * every address, because they only depend on settings, not on this process.
 */
async function stopDav() {
  const running = davServer
  davServer = null
  if (!running) return
  await running.close().catch(() => {})
  await webdav.dispose().catch(() => {})
}

/** Whether the user wants the listener running; defaults on for older installs. */
function davEnabled() {
  return storage.settings.webdav?.enabled !== false
}

function davInfo() {
  const enabled = davEnabled()

  // Switched off: the shares are still there, so show what will come back when
  // it is switched on — just without an address, because nothing is listening.
  if (!enabled) {
    return {
      available: false,
      enabled: false,
      accounts: webdav.list().map((account) => ({ ...account, url: '' })),
    }
  }

  if (!davServer) return { available: false, enabled: true, accounts: [] }
  return {
    available: true,
    enabled: true,
    origin: davServer.origin,
    port: davServer.port,
    conflict: !!davServer.conflict,
    accounts: webdav.list().map((account) => ({
      ...account,
      url: `${davServer.origin}/${account.token}/`,
    })),
  }
}

/**
 * Check the share list the settings card sent before it becomes live.
 *
 * The rules are ones the URL imposes — a token has to survive percent encoding
 * unchanged and never contain the `/` that separates path segments — plus the
 * target has to exist, or the share would only ever answer 423.
 */
function checkShares(inputs) {
  if (!Array.isArray(inputs)) throw new Error(t('errors.davToken'))
  // At least one share stays: there is no "mounting off" switch, so an empty
  // list would come back as a regenerated default on the next launch.
  if (!inputs.length) throw new Error(t('errors.davLastShare'))
  const { profiles } = storage.listProfiles()
  const ids = new Set()
  const tokens = new Set()
  const usernames = new Set()

  return inputs.map((input) => {
    const id = String(input?.id || '') || crypto.randomBytes(6).toString('hex')
    if (ids.has(id)) throw new Error(t('errors.davShare'))
    ids.add(id)

    const token = String(input?.token || '').trim()
    if (!/^[A-Za-z0-9._~-]{8,64}$/.test(token)) throw new Error(t('errors.davToken'))
    if (tokens.has(token)) throw new Error(t('errors.davTokenUsed'))
    tokens.add(token)

    // Empty means "generate one"; a name the user typed has to fit a Basic
    // header and the first path segment of a hand-written URL.
    const username = String(input.username || '').trim()
    if (username) {
      if (!/^[A-Za-z0-9._~-]{1,32}$/.test(username)) throw new Error(t('errors.davUsername'))
      const key = username.toLowerCase()
      if (usernames.has(key)) throw new Error(t('errors.davUsernameUsed'))
      usernames.add(key)
    }

    // Empty means "leave the running one alone"; main generates one for a new
    // share and seals whatever ends up live.
    const password = String(input.password || '')
    if (password && (password.length < 8 || password.length > 128)) {
      throw new Error(t('errors.davPassword'))
    }

    const kind = input.kind === 'local' || input.kind === 'cloud' ? input.kind : 'mixed'
    const profileId = kind === 'cloud' ? String(input.profileId || '') : ''
    if (kind === 'cloud' && !profiles.some((profile) => profile.id === profileId)) {
      throw new Error(t('errors.davProfile'))
    }

    return {
      id,
      name: String(input.name || '').trim() || t('settings.dav.defaultShare'),
      token,
      username,
      password,
      kind,
      profileId,
      // A mixed share spans both libraries, so a base path would be ambiguous.
      base: kind === 'mixed' ? '' : String(input.base || '').trim(),
    }
  })
}

/** Apply a new switch / port / share list from the settings card, then rebind. */
async function updateDav(input = {}) {
  const patch = {}
  let rebind = false

  if (input.port !== undefined) {
    const port = Number(input.port)
    if (!Number.isInteger(port) || port < 1024 || port > 65535) {
      throw new Error(t('errors.davPort'))
    }
    patch.port = port
    rebind = true
  }

  if (input.accounts !== undefined) {
    webdav.configure(checkShares(input.accounts))
    patch.accounts = storedShares()
    patch.token = sealSecret(webdav.list()[0]?.token || '')
  }

  if (input.enabled !== undefined) {
    patch.enabled = !!input.enabled
    rebind = true
  }

  if (Object.keys(patch).length) await storage.patchSettings({ webdav: patch })

  if (rebind) {
    // Stop first either way: switching off has to release the port, and
    // switching on (or changing it) must not inherit a stale listener.
    await stopDav()
    if (davEnabled()) await startDav()
  }

  return davInfo()
}

/** Push a snapshot to every window: progress must not be polled per window. */
function broadcaster(channel) {
  return (payload) => {
    for (const win of BrowserWindow.getAllWindows()) {
      if (!win.isDestroyed()) win.webContents.send(channel, payload)
    }
  }
}

// Finished transfers outlive the window: the queue rows are what the transfers
// page shows, and "what did I upload yesterday" is a question nobody wants to
// answer by browsing the server.
const transferHistory = new TransferHistory({
  filePath: path.join(app.getPath('userData'), 'transfers.json'),
})

// Uploads whose storage write is still running on the server: the task id is
// the only handle on them once this process is gone.
const pendingUploads = new PendingUploads({
  filePath: path.join(app.getPath('userData'), 'pending-uploads.json'),
})

const jobs = new JobQueue({ onUpdate: broadcaster('jobs:update') })
const downloads = new DownloadQueue({
  registry,
  cloud,
  localLibrary,
  history: transferHistory,
  onUpdate: broadcaster('downloads:update'),
})

// Containers are staged in the OS temp directory: encrypted, so a crash leaves
// nothing readable behind, and removed as soon as the job ends either way.
const uploads = new UploadQueue({
  cloud,
  history: transferHistory,
  pending: pendingUploads,
  onUpdate: broadcaster('uploads:update'),
  tempDir: path.join(app.getPath('temp'), 'ske-upload'),
  // A chunked upload keeps its container here until it either finishes or is
  // given up on; `temp` may be cleared by the OS between launches.
  payloadDir: path.join(app.getPath('userData'), 'upload-payloads'),
})

/**
 * Pick up uploads the server is still running after a restart.
 *
 * Only tasks that already have an id are recoverable: without one the bytes
 * never finished crossing to AList, and AList keeps nothing for an interrupted
 * body — that row is simply dropped. With one, the storage backend is writing
 * on its own clock, and this launch can watch it finish.
 *
 * A `chunk` row is the other half: the server only ever got some of the chunks,
 * and the container is still on this machine, so the job continues by sending
 * the rest of it.
 */
async function resumePendingUploads() {
  const entries = pendingUploads.entries()
  if (!entries.length) return

  // One login per profile, shared by every task that belongs to it.
  const sessions = new Map()
  const sessionFor = (profileId) => {
    if (!sessions.has(profileId)) {
      sessions.set(
        profileId,
        (async () => {
          const profile = profileId
            ? storage.listProfiles().profiles.find((item) => item.id === profileId)
            : null
          if (!profile) throw new Error(t('errors.profileMissing'))
          const secrets = storage.getSecrets(profileId)
          const opened = await cloud.login({
            profile,
            password: secrets.password,
            skePassword: secrets.skePassword,
          })
          return cloud.get(opened.sessionId)
        })(),
      )
    }
    return sessions.get(profileId)
  }

  for (const entry of entries) {
    // A chunked upload has no task to claim: only some of the chunks reached
    // the server, and the rest is still on this machine.
    if (entry.mode === 'chunk') {
      const payload = entry.payloadPath || ''
      if (!payload || !fs.existsSync(payload)) {
        // The kept container is gone, so there is nothing left to send and
        // nothing to wait for. Say so instead of leaving a phantom row.
        uploads.failPending(entry, t('errors.uploadPayloadMissing'))
        continue
      }
      const { job, signal } = uploads.adoptChunk(entry)
      // Not awaited: a slow login or a long upload must not hold the window.
      // eslint-disable-next-line no-use-before-define
      resumeChunkUpload(entry, job, signal, sessionFor)
      continue
    }
    if (!entry.taskId) {
      pendingUploads.remove(entry.id)
      continue
    }
    const { job, signal } = uploads.adopt(entry)
    // eslint-disable-next-line no-use-before-define
    resumeUploadTask(entry, job, signal, sessionFor)
  }
}

async function resumeUploadTask(entry, job, signal, sessionFor) {
  try {
    const session = await sessionFor(entry.profileId)
    await session.client.waitForUploadTask(entry.taskId, {
      size: job.uploadBytes,
      signal,
      onProgress: (progress) => {
        if (!Number.isFinite(progress?.stored)) return
        job.storedBytes = Math.min(job.uploadBytes, progress.stored)
        uploads.touch()
      },
    })
    uploads.finishRestored(job, 'done')
  } catch (err) {
    uploads.finishRestored(job, signal.aborted ? 'cancelled' : 'failed', err.message)
  }
}

async function resumeChunkUpload(entry, job, signal, sessionFor) {
  let session
  try {
    session = await sessionFor(entry.profileId)
  } catch (err) {
    // No account to send with: the container stays, the row is resolved.
    uploads.finishRestored(job, 'failed', err.message)
    return
  }
  await uploads.resumeChunk(job, session.client, { signal })
}

let server = null
/** The window that owns dialogs and window geometry; not necessarily the only one. */
let mainWindow = null
let tray = null
let quitting = false
let saveTimer = null
/** Set once `before-quit` has begun shutting things down asynchronously. */
let shuttingDown = false

/**
 * The optional AList / OpenList server running on this machine.
 *
 * Created eagerly (it only reads settings) so `settings:get` can report its
 * state, but started later: a first run may have to download ~40 MB, and the
 * window must appear before that finishes.
 */
const embedded = new EmbeddedService({
  storage,
  onUpdate: broadcaster('embedded:update'),
})
/** A `.ske` from the command line, held until a renderer exists to receive it. */
let pendingOpenFile = ''
/**
 * Every open player window. Each file gets its own: opening a second track or
 * video must never take away the window the user already has — while the same
 * file only ever has one.
 */
const stageWindows = new Set()
/** What each player window plays, keyed by its webContents id. */
const stageRoles = new Map()
/** The file each player window is showing, for "already open" lookups. */
const stageFiles = new Map()
/** Which shell renderer a player window's commands go back to. */
const stageOwners = new Map()
/** Initial payload for a fresh stage window, keyed by its webContents id. */
const stagePayloads = new Map()
/** IPC surface returned by registerIpc, used for per-window media cleanup. */
let ipcApi = null

/**
 * Window and tray icon, in the order they become available.
 *
 * `build/icon.png` is what electron-builder turns into the exe icon, and the
 * same file is copied to `resources/` so a packaged app can still show it on
 * windows and in the tray — the repository root is not part of the bundle.
 */
const ICON_CANDIDATES = [
  path.join(rootDir, 'build', 'icon.png'),
  path.join(process.resourcesPath, 'icon.png'),
  path.join(rootDir, '..', 'icon.ico'),
  path.join(rootDir, '..', 'logo.png'),
]

function resolveIcon() {
  for (const candidate of ICON_CANDIDATES) {
    try {
      if (fs.existsSync(candidate)) return candidate
    } catch {
      // Unreadable path is the same as missing.
    }
  }
  return ''
}

function activeWindow() {
  if (mainWindow && !mainWindow.isDestroyed()) return mainWindow
  return BrowserWindow.getFocusedWindow() || BrowserWindow.getAllWindows()[0] || null
}

function focusWindow() {
  const win = activeWindow()
  if (!win) return null
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
  return win
}

/** Hand a container path to the renderer, queueing it if no window exists yet. */
function sendOpenFile(filePath) {
  const win = focusWindow()
  if (!win) {
    pendingOpenFile = filePath
    return
  }
  win.webContents.send('app:openFile', {
    path: filePath,
    name: path.basename(filePath),
    dir: path.dirname(filePath),
  })
}

/** First `.ske` in argv — how Windows and Linux deliver a file association. */
function skeFileFromArgv(argv = []) {
  for (const arg of argv) {
    if (typeof arg !== 'string' || arg.startsWith('-')) continue
    if (!/\.ske$/i.test(arg)) continue
    try {
      if (fs.existsSync(arg)) return arg
    } catch {
      // Ignore unreadable candidates rather than failing the whole launch.
    }
  }
  return ''
}

/** Persist window geometry, debounced so dragging does not hammer the disk. */
function scheduleWindowSave() {
  if (!mainWindow || mainWindow.isDestroyed()) return
  clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    if (!mainWindow || mainWindow.isDestroyed()) return
    const bounds = mainWindow.getNormalBounds()
    storage.patchSettings({
      window: {
        width: bounds.width,
        height: bounds.height,
        maximized: mainWindow.isMaximized(),
      },
    }).catch(() => {})
  }, 600)
}

/**
 * Create a shell window.
 *
 * Only the first window owns geometry persistence and the close-to-tray rule;
 * extra windows are ordinary windows that simply present a route.
 */
function createWindow({
  route = '',
  page = 'index.html',
  parent = null,
  primary = null,
  width = 0,
  height = 0,
  x = null,
  y = null,
  minWidth = 1040,
  minHeight = 680,
  title = 'Sakura Encryptor',
  frame = false,
} = {}) {
  const isPrimary = primary ?? (!mainWindow || mainWindow.isDestroyed())
  const saved = storage.settings.window || {}

  const win = new BrowserWindow({
    width: width || saved.width || 1440,
    height: height || saved.height || 900,
    // Electron needs both or neither.
    ...(x != null && y != null ? { x, y } : {}),
    minWidth,
    minHeight,
    parent: parent || undefined,
    // Both the shell and the player draw their own title bar. A native caption
    // would put a grey Windows strip above the app's own one, and it cannot
    // follow the theme.
    frame,
    // Explicit: an owned window would inherit the parent's z-order on Windows,
    // which reads as "always on top" even though nobody asked for it.
    alwaysOnTop: false,
    // Matches --bg, so the window does not flash a different colour before the
    // renderer paints its first frame.
    backgroundColor: '#0a0a0a',
    show: false,
    autoHideMenuBar: true,
    title,
    // Windows and Linux take the window icon from here; a packaged build would
    // otherwise show the default Electron diamond in the taskbar.
    icon: resolveIcon() || undefined,
    webPreferences: {
      preload: preloadPath,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
    },
  })

  const webContentsId = win.webContents.id

  if (isPrimary) {
    mainWindow = win
    if (saved.maximized) win.maximize()

    win.on('resize', scheduleWindowSave)
    win.on('maximize', scheduleWindowSave)
    win.on('unmaximize', scheduleWindowSave)

    // Hiding instead of closing is only acceptable because the tray provides a
    // way back; the setting and the tray are created together.
    win.on('close', (event) => {
      if (quitting) return
      if (storage.settings.window?.closeToTray) {
        event.preventDefault()
        win.hide()
      }
    })
  }

  win.once('ready-to-show', () => win.show())

  win.on('closed', () => {
    if (mainWindow === win) {
      mainWindow = null
      // The player is an independent window now, so it no longer disappears
      // with its owner: close it explicitly when the shell really closes.
      destroyStageWindows()
    }
    // A renderer that dies without running its unmount hooks would otherwise
    // leave a decrypted stream open in the registry.
    ipcApi?.releaseMediaFor(webContentsId)
  })

  win.webContents.on('did-fail-load', (_e, code, description, url) => {
    console.error('[window] load failed', code, description, url)
  })

  // External links open in the user's browser; the app shell itself never
  // navigates away, so a malicious link cannot replace the UI with a fake one.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) shell.openExternal(url).catch(() => {})
    return { action: 'deny' }
  })

  win.webContents.once('did-finish-load', () => {
    if (isPrimary && pendingOpenFile) {
      win.webContents.send('app:openFile', {
        path: pendingOpenFile,
        name: path.basename(pendingOpenFile),
        dir: path.dirname(pendingOpenFile),
      })
      pendingOpenFile = ''
    }
  })

  // The player window is a separate HTML entry: it must not mount the shell or
  // the router, otherwise a second copy of the app would boot behind the video.
  const base = isDev ? devUrl : server.origin
  win.loadURL(page === 'index.html' ? `${base}/#${route}` : `${base}/${page}`)
  return win
}

/** Open a window on a specific hash route (used by the tray and "new window"). */
function openWindow(route = '') {
  return createWindow({ route })
}

/** Every player window that is still alive. */
function stageWindowList() {
  return [...stageWindows].filter((win) => win && !win.isDestroyed())
}

function destroyStageWindows() {
  for (const win of stageWindowList()) win.destroy()
  stageWindows.clear()
  stageRoles.clear()
  stageFiles.clear()
}

/**
 * Close the music windows.
 *
 * Used when the shell stops the track: remotes with nothing to drive would
 * only invite clicks that do nothing. `close()` (not `destroy()`) lets a window
 * release its own media session; its `closed` handler is the backstop.
 */
function closeMusicStage() {
  for (const win of stageWindowList()) {
    if (stageRoles.get(win.webContents.id) === 'audio') win.close()
  }
}

/** Initial payload for a stage window; each webContents receives it exactly once. */
function takeStagePayload(webContentsId) {
  const payload = stagePayloads.get(webContentsId) || null
  stagePayloads.delete(webContentsId)
  return payload
}

/** What a player window shows: audio means it mirrors the shell transport. */
function stageRole(payload) {
  return payload?.item?.kind === 'audio' ? 'audio' : 'media'
}

/**
 * Identity of the file a payload points at.
 *
 * The mode and session are part of it: `/a.mp4` in two cloud profiles is two
 * different files, and a local path with the same shape is a third.
 */
function stageFileKey(item) {
  if (!item) return ''
  const path = item.path || item.name
  if (!path) return ''
  return `${item.mode || 'cloud'}|${item.sessionId || ''}|${path}`
}

/**
 * Where a new player window goes: stepped off the last one, so a second file
 * does not land exactly on top of the first and read as a replacement.
 */
function cascadePosition() {
  const previous = stageWindowList().at(-1)
  if (!previous) return {}
  const { x, y } = previous.getBounds()
  return { x: x + 28, y: y + 28 }
}

/**
 * Open a player window for [payload].
 *
 * One window per file, always a new one: a window the user already has is never
 * reloaded or reused. It is an independent top-level window, not a child of the
 * shell: it must be able to sit behind the library while browsing, and only stay
 * above other windows when the user pins it.
 *
 * The same file is the exception: it already has a window, so opening it again
 * brings that one forward instead of building a second copy of the same video.
 * `payload.reuse` is the looser version of the same idea, for the sidebar's
 * "open the music page": come back to the remote, don't add another.
 */
function openStage(payload, ownerId = 0) {
  const key = stageFileKey(payload?.item)
  const existing = key
    ? stageWindowList().find((win) => stageFiles.get(win.webContents.id) === key)
    : null

  if (existing) {
    if (existing.isMinimized()) existing.restore()
    existing.show()
    existing.focus()
    return true
  }

  if (payload?.reuse) {
    const audio = stageWindowList().find((win) => stageRoles.get(win.webContents.id) === 'audio')
    if (audio) {
      if (audio.isMinimized()) audio.restore()
      audio.show()
      audio.focus()
      return true
    }
  }

  // Deliberately *not* a child window: on Windows an owned window inherits the
  // parent's z-order, so "always on top" would be silently ignored, and
  // minimising the shell would take the video down with it. The player has to be
  // a normal top-level window to be pinnable and usable on a second screen.
  const win = createWindow({
    page: 'stage.html',
    primary: false,
    width: 1024,
    height: 640,
    ...cascadePosition(),
    minWidth: 480,
    minHeight: 320,
    title: payload?.item?.name || t('common.playerWindow'),
    // Frameless: the in-window bar carries drag, minimise, maximise and close.
    frame: false,
  })

  const id = win.webContents.id
  stageWindows.add(win)
  stageRoles.set(id, stageRole(payload))
  stageFiles.set(id, key)
  stagePayloads.set(id, payload)
  if (ownerId) stageOwners.set(id, ownerId)

  win.on('closed', () => {
    stageWindows.delete(win)
    stageRoles.delete(id)
    stageFiles.delete(id)
    stagePayloads.delete(id)
    stageOwners.delete(id)
  })

  return true
}

/** The shell a player window reports to, for routing its commands back. */
function stageOwnerOf(webContentsId) {
  const ownerId = stageOwners.get(webContentsId)
  if (!ownerId) return null
  const owner = webContents.fromId(ownerId)
  return owner && !owner.isDestroyed() ? owner : null
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  pendingOpenFile = skeFileFromArgv(process.argv)

  app.on('second-instance', (_event, argv) => {
    const file = skeFileFromArgv(argv)
    if (file) {
      sendOpenFile(file)
      return
    }
    focusWindow()
  })

  // macOS delivers file associations through an event rather than argv.
  app.on('open-file', (event, filePath) => {
    event.preventDefault()
    if (app.isReady()) sendOpenFile(filePath)
    else pendingOpenFile = filePath
  })
}

app.whenReady().then(async () => {
  await storage.load()

  // Rows finished in a previous run go back into the same maps as live jobs, so
  // the transfers page renders history without a special case for it.
  await transferHistory.load()
  downloads.restore(transferHistory.recordsFor('download'))
  uploads.restore(transferHistory.recordsFor('upload'))

  // Uploads the server may still be writing: adopted into the queue and watched
  // in the background, so the transfers page shows them moving again.
  await pendingUploads.load()
  // Containers left behind by a crash are dead weight; keep only the ones a
  // pending row still needs before anything is adopted.
  await uploads.sweepPayloads().catch(() => {})
  resumePendingUploads().catch(() => {})

  // Before any window exists: the shell and the tray both ask for strings, and
  // `auto` can only be resolved here, where `app.getLocale()` is available.
  applyLanguage(storage.settings.language, app.getLocale())
  await security.load()

  server = await startServer({
    registry,
    cloud,
    rendererDir: isDev ? null : rendererDir,
  })

  console.log(`[ske] loopback origin: ${server.origin}`)

  // The shares exist whether or not the listener does: the settings card shows
  // them while mounting is off, and switching it on must not need a restart.
  webdav.configure(davShares())

  // Off only when the user switched it off. A busy port must not stop the app
  // from starting either: the listener falls back to a free one and the
  // settings card says so.
  if (davEnabled()) {
    await startDav().catch((err) => {
      console.error('[dav] cannot start:', err.message)
      davServer = null
    })
  }

  if (!isDev) Menu.setApplicationMenu(null)

  ipcApi = registerIpc({
    storage,
    security,
    registry,
    cloud,
    localLibrary,
    jobs,
    downloads,
    uploads,
    embedded,
    webdav,
    davInfo,
    updateDav,
    serverInfo: () => (server ? { origin: server.origin, token: server.token } : null),
    mainWindow: activeWindow,
    openWindow,
    openStage,
    takeStagePayload,
    stageWindows: stageWindowList,
    stageOwnerOf,
    closeMusicStage,
    // Locking closes the player windows and forgets the mounted libraries:
    // leaving either behind would defeat the lock.
    onLock: () => {
      destroyStageWindows()
      webdav.clearCloudSession()
      webdav.dispose().catch(() => {})
    },
    // The tray holds a native menu with the strings it was built with, so a
    // language switch has to hand it a newly built one.
    onLocaleChange: () => tray?.refresh?.(),
  })

  // Deliberately not awaited: a first run downloads the server, and the window
  // has to be usable while that happens. The settings page follows the
  // `embedded:update` stream.
  embedded.restore().catch(() => {})

  createWindow()

  // Warm the external-player detection in the background: the first right-click
  // should not pay for the registry queries behind it.
  listPlayers().catch(() => {})

  tray = createTray({
    iconPath: resolveIcon(),
    t,
    onShow: () => {
      if (!focusWindow()) openWindow('/browse')
    },
    onNewWindow: () => openWindow('/browse'),
    onQuit: () => {
      quitting = true
      app.quit()
    },
  })

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
    else focusWindow()
  })
})

app.on('window-all-closed', () => {
  if (quitting) return
  // With close-to-tray on, the app is expected to survive having no windows;
  // the tray icon is the only remaining entry point.
  if (tray && storage.settings.window?.closeToTray) return
  if (process.platform !== 'darwin') app.quit()
})

app.on('before-quit', async (event) => {
  quitting = true
  // Electron does not await this handler, so a child process that needs a
  // graceful stop would simply be orphaned. Hold the quit, run the cleanup, then
  // exit explicitly.
  if (shuttingDown) return
  shuttingDown = true
  event.preventDefault()

  clearTimeout(saveTimer)
  // Seal the transfer history while the queue rows still mean something: the
  // cancels below are this quit's doing, not the user's.
  transferHistory.stop()
  // What is still in flight has to be on disk *before* the queues are torn
  // down: those rows are what the next launch resumes.
  pendingUploads.flush()
  jobs.cancelAll()
  downloads.cancelAll()
  cloud.logoutAll()
  tray?.destroy()
  tray = null
  await davServer?.close().catch(() => {})
  davServer = null
  await webdav.dispose().catch(() => {})
  await registry.closeAll().catch(() => {})
  // Before the media server: the built-in server owns a child process whose
  // database wants a clean shutdown on POSIX.
  await embedded.shutdown().catch(() => {})
  await server?.close().catch(() => {})
  app.exit(0)
})
