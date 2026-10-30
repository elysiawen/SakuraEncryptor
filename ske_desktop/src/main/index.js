import { app, BrowserWindow, Menu, shell, webContents } from 'electron'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { MediaRegistry } from './media-router.js'
import { startServer } from './server.js'
import { Storage } from './storage.js'
import { Security } from './security.js'
import { CloudService } from './cloud.js'
import { ThumbDiskCache } from './thumb-cache.js'
import { LocalLibrary } from './local-fs.js'
import { UploadQueue } from './uploads.js'
import { JobQueue } from './jobs.js'
import { DownloadQueue } from './downloads.js'
import { listPlayers } from './external-player.js'
import { createTray } from './tray.js'
import { registerIpc } from './ipc.js'

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

/** Push a snapshot to every window: progress must not be polled per window. */
function broadcaster(channel) {
  return (payload) => {
    for (const win of BrowserWindow.getAllWindows()) {
      if (!win.isDestroyed()) win.webContents.send(channel, payload)
    }
  }
}

const jobs = new JobQueue({ onUpdate: broadcaster('jobs:update') })
const downloads = new DownloadQueue({
  registry,
  cloud,
  localLibrary,
  onUpdate: broadcaster('downloads:update'),
})

// Containers are staged in the OS temp directory: encrypted, so a crash leaves
// nothing readable behind, and removed as soon as the job ends either way.
const uploads = new UploadQueue({
  cloud,
  onUpdate: broadcaster('uploads:update'),
  tempDir: path.join(app.getPath('temp'), 'ske-upload'),
})

let server = null
/** The window that owns dialogs and window geometry; not necessarily the only one. */
let mainWindow = null
let tray = null
let quitting = false
let saveTimer = null
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
    title: payload?.item?.name || '播放器',
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
  await security.load()

  server = await startServer({
    registry,
    cloud,
    rendererDir: isDev ? null : rendererDir,
  })

  console.log(`[ske] loopback origin: ${server.origin}`)

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
    serverInfo: () => (server ? { origin: server.origin, token: server.token } : null),
    mainWindow: activeWindow,
    openWindow,
    openStage,
    takeStagePayload,
    stageWindows: stageWindowList,
    stageOwnerOf,
    closeMusicStage,
    // Locking closes the player windows: leaving a video over the lock screen
    // would defeat the lock.
    onLock: destroyStageWindows,
  })

  createWindow()

  // Warm the external-player detection in the background: the first right-click
  // should not pay for the registry queries behind it.
  listPlayers().catch(() => {})

  tray = createTray({
    iconPath: resolveIcon(),
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

app.on('before-quit', async () => {
  quitting = true
  clearTimeout(saveTimer)
  jobs.cancelAll()
  downloads.cancelAll()
  cloud.logoutAll()
  tray?.destroy()
  tray = null
  await registry.closeAll().catch(() => {})
  await server?.close().catch(() => {})
})
