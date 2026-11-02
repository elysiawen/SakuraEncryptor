/**
 * All IPC channels in one place.
 *
 * Every handler returns `{ ok: true, ...payload }` or `{ ok: false, error }`
 * instead of throwing: Electron wraps thrown errors in a message that hides the
 * real cause, and the UI needs to show the cause verbatim.
 */
import { BrowserWindow, app, clipboard, dialog, ipcMain, screen, shell } from 'electron'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { listPlayers, playExternal } from './external-player.js'
import { decryptName, deriveNameKey, encryptName } from './ske-names.js'
import { applyLanguage, currentLocale, t } from './i18n.js'

function handle(channel, fn) {
  ipcMain.handle(channel, async (event, ...args) => {
    try {
      const result = await fn(event, ...args)
      return { ok: true, ...(result ?? {}) }
    } catch (err) {
      // `needsOtp` is the only machine-readable flag the UI acts on: AList and
      // OpenList both answer 402 when an account wants a one-time code, and the
      // renderer has to prompt for it rather than show a dead-end error.
      return { ok: false, error: err?.message || String(err), ...(err?.needsOtp ? { needsOtp: true } : {}) }
    }
  })
}

/** Subtitle containers are plain text; anything bigger is not a subtitle. */
const MAX_SUBTITLE_BYTES = 2 * 1024 * 1024

/** Language tags that make `song.zh.lrc` a better pick than `song.foo.lrc`. */
const LYRICS_LANG_TAGS = [
  'zh',
  'zh-cn',
  'zh-hans',
  'zh-sg',
  'chs',
  'zh-tw',
  'zh-hant',
  'cht',
  'en',
  'ja',
  'jp',
  'ko',
]

/**
 * Subtitles in the wild are UTF-8, UTF-16 with a BOM, or legacy GBK.
 * Decoding happens here so the renderer never needs an iconv shim.
 */
function decodeSubtitleBuffer(buf) {
  if (buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) {
    return buf.subarray(3).toString('utf8')
  }
  if (buf.length >= 2 && buf[0] === 0xff && buf[1] === 0xfe) {
    return new TextDecoder('utf-16le').decode(buf.subarray(2))
  }
  if (buf.length >= 2 && buf[0] === 0xfe && buf[1] === 0xff) {
    return new TextDecoder('utf-16be').decode(buf.subarray(2))
  }

  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buf)
  } catch {
    try {
      return new TextDecoder('gbk').decode(buf)
    } catch {
      return buf.toString('utf8')
    }
  }
}

/**
 * @param {object} deps
 * @param {import('./storage.js').Storage} deps.storage
 * @param {import('./security.js').Security} deps.security
 * @param {import('./media-router.js').MediaRegistry} deps.registry
 * @param {import('./cloud.js').CloudService} deps.cloud
 * @param {import('./local-fs.js').LocalLibrary} deps.localLibrary
 * @param {import('./jobs.js').JobQueue} deps.jobs
 * @param {import('./downloads.js').DownloadQueue} deps.downloads
 * @param {import('./uploads.js').UploadQueue} deps.uploads
 * @param {import('./embedded/index.js').EmbeddedService} deps.embedded
 * @param {import('./webdav.js').WebdavGateway} [deps.webdav]
 * @param {() => { available: boolean, origin?: string, port?: number, url?: string }} [deps.davInfo]
 * @param {(patch: { port?: number, token?: string }) => Promise<object>} [deps.updateDav]
 * @param {() => { origin: string, token: string } | null} deps.serverInfo
 * @param {() => import('electron').BrowserWindow | null} deps.mainWindow
 * @param {(route?: string) => import('electron').BrowserWindow | null} [deps.openWindow]
 * @param {(payload: object) => boolean} [deps.openStage]
 * @param {(webContentsId: number) => object | null} [deps.takeStagePayload]
 * @param {() => import('electron').BrowserWindow[]} [deps.stageWindows]
 * @param {(webContentsId: number) => import('electron').WebContents | null} [deps.stageOwnerOf]
 * @param {() => void} [deps.closeMusicStage]
 * @param {() => void} [deps.onLock]
 * @param {() => void} [deps.onLocaleChange]  rebuild anything that captured the old strings
 */
export function registerIpc({
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
  serverInfo,
  mainWindow,
  openWindow,
  openStage,
  takeStagePayload,
  stageWindows,
  stageOwnerOf,
  closeMusicStage,
  onLock,
  onLocaleChange,
}) {
  /** Secrets of the active profile, used as the default encryption password. */
  function activeSecrets() {
    const { activeId } = storage.listProfiles()
    return activeId ? storage.getSecrets(activeId) : null
  }

  /**
   * media session id -> webContents id that opened it.
   *
   * A renderer that is destroyed (player window closed, window crash) never runs
   * its unmount hooks, so the registry would keep decrypting for nobody. This
   * map lets the window's `closed` event release exactly that window's streams.
   */
  const mediaOwners = new Map()
  handle('app:info', () => {
    const server = serverInfo()
    return {
      origin: server?.origin ?? '',
      version: app.getVersion(),
      platform: process.platform,
      userData: app.getPath('userData'),
    }
  })

  // --- launch lock ---------------------------------------------------------

  /**
   * Settings that change the gate itself must not be reachable from the lock
   * screen: rebinding a factor there would be a way around it.
   */
  const requireUnlocked = () => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
  }

  handle('security:state', () => ({ state: security.state() }))

  // Every handler below awaits its promise *before* building the reply object.
  // A promise nested inside the returned value survives `await` on the outer
  // call, then fails structured cloning with a bare "could not be cloned".
  handle('security:unlock', async (_e, input) => ({ state: await security.unlock(input) }))

  handle('security:lock', () => {
    const state = security.lock()
    // A player window left open over the lock screen would defeat the lock.
    onLock?.()
    return { state }
  })

  handle('security:setPassword', async (_e, password, currentPassword) => {
    requireUnlocked()
    return { state: await security.setPassword(password, currentPassword) }
  })

  handle('security:disable', async (_e, input) => ({ state: await security.disable(input) }))

  // TOTP factor. `begin` only proposes a secret; `confirm` writes it once a code
  // generated from it proves the user actually imported it.
  handle('security:totpBegin', () => {
    requireUnlocked()
    return security.beginTotp()
  })

  handle('security:totpConfirm', async (_e, { code, secret } = {}) => {
    requireUnlocked()
    return { state: await security.confirmTotp(code, secret) }
  })

  handle('security:totpUnbind', async () => {
    requireUnlocked()
    return { state: await security.unbindTotp() }
  })

  handle('security:setMatch', async (_e, match) => {
    requireUnlocked()
    return { state: await security.setMatch(match) }
  })

  // --- profiles ------------------------------------------------------------

  handle('vault:list', () => storage.listProfiles())

  handle('vault:save', async (_e, profile) => ({
    profile: await storage.saveProfile(profile),
    ...storage.listProfiles(),
  }))

  handle('vault:delete', async (_e, id) => ({
    deleted: await storage.deleteProfile(id),
    ...storage.listProfiles(),
  }))

  handle('vault:activate', async (_e, id) => ({
    activeId: await storage.setActiveProfile(id),
    ...storage.listProfiles(),
  }))

  // --- settings ------------------------------------------------------------

  /**
   * Both settings calls report `locale` next to `settings`.
   *
   * The renderer stores the user's *choice* (`auto` included); only this process
   * can turn `auto` into a code, so it answers with the resolved value instead
   * of letting each window guess from `navigator.language`.
   */
  handle('settings:get', () => ({ settings: storage.settings, locale: currentLocale() }))

  handle('settings:patch', async (_e, patch) => {
    const settings = await storage.patchSettings(patch)
    // The preview cache has to follow immediately: a switch that only takes
    // effect on the next launch leaves files the user just asked to remove.
    const cachePatch = patch?.cache
    if (cachePatch && typeof cachePatch === 'object') {
      if ('thumbEnabled' in cachePatch || 'thumbLimitMB' in cachePatch) {
        await cloud.syncThumbCache()
      }
    }
    // A language write is not just data: the tray menu baked the old strings
    // into a native menu, so it has to be rebuilt here rather than next launch.
    if (patch && typeof patch === 'object' && 'language' in patch) {
      applyLanguage(settings.language, app.getLocale())
      onLocaleChange?.()
    }
    return { settings, locale: currentLocale() }
  })

  // --- dialogs -------------------------------------------------------------

  /** Pick a container to *decrypt*: the `.ske` filter belongs here and only here. */
  handle('dialog:pickFile', async () => {
    const win = mainWindow()
    if (!win) return { path: null }
    const result = await dialog.showOpenDialog(win, {
      title: t('dialogs.pickContainer'),
      properties: ['openFile'],
      filters: [
        { name: t('dialogs.filterContainer'), extensions: ['ske'] },
        { name: t('dialogs.filterAllFiles'), extensions: ['*'] },
      ],
    })
    return { path: result.canceled ? null : result.filePaths[0] }
  })

  /**
   * Pick plaintext sources to encrypt.
   *
   * A different dialog from the container picker, and deliberately unfiltered:
   * the writer encrypts any byte stream, so restricting the picker to media (or
   * to `.ske`) would only hide files the user is allowed to encrypt. Multi-select
   * because batching is the normal case.
   */
  handle('dialog:pickSourceFiles', async () => {
    const win = mainWindow()
    if (!win) return { paths: [] }
    const result = await dialog.showOpenDialog(win, {
      title: t('dialogs.pickSources'),
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: t('dialogs.filterAllFiles'), extensions: ['*'] }],
    })
    return { paths: result.canceled ? [] : result.filePaths }
  })

  /**
   * Pick files to upload to the current cloud folder.
   *
   * Sizes come back with the paths so the confirmation dialog can show what is
   * about to be pushed; the renderer cannot stat a local file itself.
   */
  handle('dialog:pickUploadFiles', async () => {
    const win = mainWindow()
    if (!win) return { files: [] }
    const result = await dialog.showOpenDialog(win, {
      title: t('dialogs.pickUpload'),
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: t('dialogs.filterAllFiles'), extensions: ['*'] }],
    })
    if (result.canceled) return { files: [] }

    const files = []
    for (const filePath of result.filePaths) {
      try {
        const stat = await fsp.stat(filePath)
        if (!stat.isFile()) continue
        files.push({ path: filePath, name: path.basename(filePath), size: stat.size })
      } catch {
        // Gone between the picker closing and this stat: skip it rather than
        // failing the whole batch.
      }
    }
    return { files }
  })

  /** Pick the player that the "外部播放器" command should launch. */
  handle('dialog:pickPlayer', async () => {
    const win = mainWindow()
    if (!win) return { path: null }
    const result = await dialog.showOpenDialog(win, {
      title: t('dialogs.pickPlayer'),
      properties: ['openFile'],
      filters:
        process.platform === 'win32'
          ? [
              { name: t('dialogs.filterExecutable'), extensions: ['exe'] },
              { name: t('dialogs.filterAllFiles'), extensions: ['*'] },
            ]
          : [{ name: t('dialogs.filterAllFiles'), extensions: ['*'] }],
    })
    return { path: result.canceled ? null : result.filePaths[0] }
  })

  handle('dialog:pickDirectory', async () => {
    const win = mainWindow()
    if (!win) return { path: null }
    const result = await dialog.showOpenDialog(win, {
      title: t('dialogs.pickDirectory'),
      properties: ['openDirectory'],
    })
    return { path: result.canceled ? null : result.filePaths[0] }
  })

  // --- embedded server -----------------------------------------------------

  /**
   * Every mutating call returns the refreshed profile list as well: the built-in
   * server owns one profile whose address and credentials it rewrites, and the
   * renderer must not keep rendering the stale one.
   */
  function embeddedPayload(state) {
    return { state, ...storage.listProfiles() }
  }

  handle('embedded:state', async () => ({ state: await embedded.snapshot() }))

  handle('embedded:enable', async (_e, backendId) => embeddedPayload(await embedded.enable(backendId)))

  handle('embedded:disable', async () => {
    await embedded.disable()
    // Any session was talking to a server that has just stopped.
    cloud.logoutAll()
    return embeddedPayload(await embedded.snapshot())
  })

  handle('embedded:reinstall', async (_e, backendId) => embeddedPayload(await embedded.reinstall(backendId)))

  handle('embedded:setBackend', async (_e, backendId) => embeddedPayload(await embedded.setBackend(backendId)))

  handle('embedded:setPort', async (_e, port) => embeddedPayload(await embedded.setPort(port)))

  handle('embedded:setVersion', async (_e, version) => embeddedPayload(await embedded.setVersion(version)))

  handle('embedded:setMirror', async (_e, mirror) => embeddedPayload(await embedded.setMirror(mirror)))

  handle('embedded:account', async () => ({ account: await embedded.account() }))

  handle('embedded:updateAccount', async (_e, patch = {}) => {
    const account = await embedded.updateAccount(patch)
    return { account, ...embeddedPayload(await embedded.snapshot()) }
  })

  handle('embedded:revealPassword', async () => ({ password: await embedded.revealPassword() }))

  handle('embedded:randomPassword', () => ({ password: embedded.regeneratePassword() }))

  handle('embedded:storages', async () => ({ storages: await embedded.storages() }))

  handle('embedded:drivers', async () => ({ drivers: await embedded.drivers() }))

  handle('embedded:driverInfo', async (_e, driver) => ({ info: await embedded.driverInfo(driver) }))

  handle('embedded:createStorage', async (_e, payload = {}) => ({ storages: await embedded.createStorage(payload) }))

  handle('embedded:updateStorage', async (_e, payload = {}) => ({ storages: await embedded.updateStorage(payload) }))

  handle('embedded:deleteStorage', async (_e, id) => ({ storages: await embedded.deleteStorage(id) }))

  handle('embedded:setStorageEnabled', async (_e, id, enabled) => ({
    storages: await embedded.setStorageEnabled(id, enabled),
  }))

  handle('embedded:logs', () => ({ logs: embedded.logs() }))

  handle('embedded:openDir', async () => {
    await embedded.openDataDir()
    return {}
  })

  handle('embedded:uninstall', async (_e, backendId) => embeddedPayload(await embedded.uninstall(backendId)))

  // --- cloud ---------------------------------------------------------------

  handle('cloud:connect', async (_e, profileId, otpCode = '') => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))

    const { profiles } = storage.listProfiles()
    const profile = profiles.find((p) => p.id === profileId)
    if (!profile) throw new Error(t('errors.profileMissing'))

    // Secrets are unsealed here and handed straight to CloudService; they never
    // travel back through IPC. `otpCode` comes from the renderer because a
    // one-time code cannot be stored: OpenList asks for it on every login.
    const secrets = storage.getSecrets(profileId) || {}
    const result = await cloud.login({
      profile,
      password: secrets.password,
      skePassword: secrets.skePassword,
      otpCode: typeof otpCode === 'string' ? otpCode.trim() : '',
    })
    // The drive mount follows the profile the user is actually looking at, so
    // the WebDAV folder always mirrors the connected library.
    webdav?.setCloudSession(result.sessionId)
    return { session: result }
  })

  handle('cloud:status', (_e, sessionId) => ({ status: cloud.status(sessionId) }))

  handle('cloud:disconnect', (_e, sessionId) => {
    cloud.logout(sessionId)
    // `setCloudSession` ignores an empty id, so clear explicitly.
    if (webdav && webdav.cloudSessionId === sessionId) webdav.clearCloudSession()
    return { disconnected: true }
  })

  // --- drive mount (WebDAV) ------------------------------------------------

  /** One shape for "read" and "after an edit", so the card cannot diverge. */
  function davPayload(info) {
    if (!webdav) return { enabled: false, accounts: [] }
    return {
      enabled: info?.enabled !== false,
      // Each share's token is its root, not a segment under it: Windows keys a
      // WebDAV connection on whatever follows `@port` and drops deeper paths,
      // so the credential has to sit exactly there. The port and the tokens
      // come from settings, which is what keeps a mapped drive pointing at the
      // same place after a restart.
      port: info?.port || 0,
      conflict: !!info?.conflict,
      accounts: info?.accounts || [],
    }
  }

  handle('webdav:info', () => davPayload(davInfo?.()))

  handle('webdav:update', async (_e, patch) => {
    if (!updateDav) throw new Error(t('errors.serverNotReady'))
    return davPayload(await updateDav(patch || {}))
  })

  /** The gateway speaks WebDAV; the settings card speaks the user's language. */
  const DAV_BROWSE_ERRORS = {
    localLocked: 'errors.davLocalLocked',
    localEmpty: 'errors.davNoRoot',
    noProfile: 'errors.davProfile',
    noSession: 'errors.davProfile',
    profileMissing: 'errors.davProfileGone',
    profileLocked: 'errors.davProfilePassword',
    needsOtp: 'errors.davProfileOtp',
  }

  /**
   * One folder of a target, for the picker in the share dialog.
   *
   * Listing a cloud folder logs the profile in exactly the way a mounted
   * request would, so the picker shows what a mounted request would see. It
   * works while mounting is off: configuring a share before switching the
   * listener on is the point.
   */
  handle('webdav:browse', async (_e, target) => {
    if (!webdav) throw new Error(t('errors.serverNotReady'))
    try {
      return await webdav.browse(target || {})
    } catch (err) {
      if (err?.reason === 'loginFailed') {
        throw new Error(t('errors.davProfileLogin', { message: err.message }))
      }
      const key = DAV_BROWSE_ERRORS[err?.reason]
      if (key) throw new Error(t(key))
      if (err?.code === 'ENOENT') throw new Error(t('errors.davFolderMissing'))
      throw err
    }
  })

  /**
   * Hand one share to the OS file manager. Windows speaks WebDAV over a UNC
   * path (`\\host@port\share`), which Explorer turns into a browsable location
   * without a drive letter and without us choosing one for the user.
   */
  handle('webdav:open', async (_e, accountId) => {
    const info = davInfo?.()
    if (!info?.available) throw new Error(t('errors.serverNotReady'))
    if (process.platform !== 'win32') return { opened: false }

    const account = info.accounts?.find((item) => item.id === accountId) || info.accounts?.[0]
    if (!account) throw new Error(t('errors.serverNotReady'))

    const url = new URL(info.origin)
    // `\\host@port\share` — the share is its token, and the redirector will
    // PROPFIND exactly that path.
    const unc = `\\\\${url.hostname}@${url.port}\\${account.token}`
    const error = await shell.openPath(unc)
    return { opened: !error, error, unc }
  })

  handle('cloud:list', async (_e, { sessionId, path, refresh } = {}) => {
    const result = await cloud.list(sessionId, path, { refresh: !!refresh })
    const server = serverInfo()
    const base = server?.origin || ''

    // Preview URLs are built here so the renderer only needs a plain <img src>.
    // A container previews from its encrypted envelope; a plain file previews
    // from the picture the storage already made, proxied so the tight CSP keeps
    // holding and the signed link never reaches the renderer.
    const token = server?.token || ''
    for (const entry of result.entries) {
      if (entry.isDir) continue
      const encoded = Buffer.from(entry.path, 'utf8').toString('base64url')
      if (entry.encrypted) {
        entry.thumbUrl = `${base}/thumb/${sessionId}/${encoded}?t=${token}`
      } else if (entry.hasThumb) {
        entry.thumbUrl = `${base}/remote-thumb/${sessionId}/${encoded}?t=${token}`
      }
    }
    return result
  })

  /**
   * File-manager writes.
   *
   * Names are encrypted on this side: the renderer types plaintext, the stored
   * tree keeps tokens, and a plain entry stays plain (see `CloudService.rename`).
   */
  handle('cloud:mkdir', (_e, { sessionId, path, name, encrypt } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    return cloud.makeDir(sessionId, path, name, { encrypt })
  })

  handle('cloud:rename', (_e, { sessionId, path, newName } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    return cloud.rename(sessionId, path, newName)
  })

  handle('cloud:remove', (_e, { sessionId, path } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    return cloud.remove(sessionId, path)
  })

  // --- media ---------------------------------------------------------------

  handle('media:open', async (event, payload = {}) => {
    if (security.isEnabled() && !security.unlocked) {
      throw new Error(t('errors.locked'))
    }
    const server = serverInfo()
    if (!server) throw new Error(t('errors.serverNotReady'))

    const info = await registry.open({
      target: payload.target,
      password: payload.password,
      name: payload.name,
      headers: payload.headers,
    })

    mediaOwners.set(info.id, event.sender.id)

    return {
      ...info,
      url: `${server.origin}/media/${info.id}?t=${server.token}`,
    }
  })

  /**
   * Play a cloud file: resolve the signed URL and open the container in one
   * step, so the media password never has to reach the renderer.
   */
  handle('media:openCloud', async (event, { sessionId, path, name }) => {
    if (security.isEnabled() && !security.unlocked) {
      throw new Error(t('errors.locked'))
    }
    const server = serverInfo()
    if (!server) throw new Error(t('errors.serverNotReady'))

    const session = cloud.get(sessionId)
    const { rawUrl } = await cloud.resolve(sessionId, path)

    const info = await registry.open({
      target: rawUrl,
      password: session.skePassword,
      name: name || path,
    })

    mediaOwners.set(info.id, event.sender.id)

    return {
      ...info,
      url: `${server.origin}/media/${info.id}?t=${server.token}`,
    }
  })

  handle('media:close', async (_e, id) => {
    mediaOwners.delete(id)
    return { closed: await registry.close(id) }
  })

  /**
   * Read a subtitle/lyric file as text.
   *
   * Opened as a throwaway session: the renderer never receives a URL (no CORS
   * to open up, no decrypt cache left behind) and the media password stays on
   * this side, exactly like playback.
   */
  async function readTextFile({ mode, path, name, sessionId }) {
    let target = ''
    let password = ''

    if (mode === 'local') {
      if (!localLibrary.unlocked) throw new Error(t('errors.localLocked'))
      target = localLibrary.filePath(path)
      password = localLibrary.password
    } else {
      const session = cloud.get(sessionId)
      if (!session) throw new Error(t('errors.cloudDisconnected'))
      target = (await cloud.resolve(sessionId, path)).rawUrl
      password = session.skePassword
    }

    const info = await registry.open({ target, password, name: name || path })
    try {
      if (info.size > MAX_SUBTITLE_BYTES) throw new Error(t('errors.tooLargeText'))
      if (info.size === 0) throw new Error(t('errors.emptyFile'))

      const opened = registry.get(info.id)
      const chunks = []
      for await (const chunk of opened.readRange(0, info.size - 1)) chunks.push(Buffer.from(chunk))

      return { text: decodeSubtitleBuffer(Buffer.concat(chunks)), size: info.size }
    } finally {
      await registry.close(info.id).catch(() => {})
    }
  }

  handle('subtitles:read', async (_e, { mode, path, name, sessionId } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    if (!path) throw new Error(t('errors.subtitleMissing'))
    return readTextFile({ mode, path, name, sessionId })
  })

  /**
   * Find and read the `.lrc` sitting next to the playing track.
   *
   * Discovery happens here because the music window has no folder listing: the
   * file name is the only link between the two. An exact `<track>.lrc` wins,
   * then language variants (`<track>.zh.lrc`) — the same shape subtitles use.
   */
  handle('lyrics:read', async (_e, { mode, path, name, sessionId } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    if (!path) return { text: '', size: 0 }

    const dir = path.slice(0, path.lastIndexOf('/')) || '/'
    const file = String(name || path).split('/').pop()
    const base = file.replace(/\.[^.]+$/, '').toLowerCase()

    let listing = null
    try {
      listing =
        mode === 'local'
          ? await localLibrary.list(dir)
          : await cloud.list(sessionId, dir)
    } catch {
      // A folder that cannot be listed is not an error worth a dialog: the
      // lyrics pane simply stays on its empty state.
      return { text: '', size: 0 }
    }

    const ranked = []
    for (const entry of listing?.entries || []) {
      if (entry.isDir || !/\.lrc$/i.test(entry.name)) continue
      const stem = entry.name.slice(0, -4).toLowerCase()
      if (stem === base) ranked.push({ entry, rank: 0 })
      else if (stem.startsWith(`${base}.`)) {
        const tag = stem.slice(base.length + 1)
        ranked.push({ entry, rank: LYRICS_LANG_TAGS.includes(tag) ? 1 : 2 })
      }
    }
    if (!ranked.length) return { text: '', size: 0 }

    ranked.sort((a, b) => a.rank - b.rank || a.entry.name.localeCompare(b.entry.name))
    const chosen = ranked[0].entry
    return readTextFile({ mode, path: chosen.path, name: chosen.name, sessionId })
  })

  // --- local library -------------------------------------------------------

  handle('local:unlock', (_e, password) => ({
    status: localLibrary.unlock(password),
    ...localLibrary.status(),
  }))

  handle('local:lock', () => ({ ...localLibrary.lock() }))

  handle('local:status', () => localLibrary.status())

  handle('local:setRoot', (_e, dir) => ({
    ...localLibrary.setRoot(dir),
    ...localLibrary.status(),
  }))

  handle('local:list', (_e, path) => localLibrary.list(path))

  // Same writes as the cloud library, applied to the selected directory on disk.
  handle('local:mkdir', (_e, { path, name, encrypt } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    return localLibrary.makeDir(path, name, { encrypt })
  })

  handle('local:rename', (_e, { path, newName } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    return localLibrary.rename(path, newName)
  })

  handle('local:remove', (_e, { path } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    return localLibrary.remove(path)
  })

  /**
   * Play a local container. The password stays here: the renderer only receives
   * a stream URL, same as the cloud path.
   */
  handle('local:play', async (event, { path, name }) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    if (!localLibrary.unlocked) throw new Error(t('errors.localLocked'))

    const server = serverInfo()
    if (!server) throw new Error(t('errors.serverNotReady'))

    const target = localLibrary.filePath(path)
    const info = await registry.open({
      target,
      password: localLibrary.password,
      name: name || target.split(/[\\/]/).pop(),
    })

    mediaOwners.set(info.id, event.sender.id)

    return { ...info, url: `${server.origin}/media/${info.id}?t=${server.token}` }
  })

  // --- encryption jobs -----------------------------------------------------

  handle('jobs:list', () => ({ jobs: jobs.snapshot() }))

  handle('jobs:add', (_e, payload = {}) => {
    const { srcPath, destDir, password } = payload
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))

    // An empty password field means "use the active profile's media password",
    // which is what most users expect when they only have one vault.
    const secret = password || activeSecrets()?.skePassword || ''
    if (!secret) throw new Error(t('errors.needSecret'))

    return {
      job: jobs.add({
        srcPath,
        destDir,
        password: secret,
        nameKey: deriveNameKey(secret),
        // Not a caller switch: every media container gets its preview, because
        // a missing one is permanent. `JobQueue` keeps the option only so tests
        // can skip ffmpeg.
        thumbnails: true,
      }),
    }
  })

  handle('jobs:cancel', (_e, id) => ({ cancelled: jobs.cancel(id), jobs: jobs.snapshot() }))

  handle('jobs:clear', () => ({ jobs: jobs.clearFinished() }))

  // --- text converter ------------------------------------------------------

  /**
   * Encrypt or decrypt a snippet with the file-name key scheme.
   *
   * The same primitive backs the Android text panel, the CLI's text tab and the
   * web client's name tool, so a token made here decrypts there and vice versa —
   * as long as both sides use the same password. Only the result crosses back;
   * the password never leaves the main process.
   */
  handle('text:convert', (_e, { direction, text, password } = {}) => {
    requireUnlocked()
    const source = typeof text === 'string' ? text.trim() : ''
    if (!source) throw new Error(t('errors.emptyInput'))

    // Same fallback as the encryption queue: an empty field means "use the
    // active profile's media password".
    const secret = String(password || '') || activeSecrets()?.skePassword || ''
    if (!secret) throw new Error(t('errors.needSecret'))

    const key = deriveNameKey(secret)
    if (direction === 'decrypt') {
      try {
        return { text: decryptName(source, key) }
      } catch {
        throw new Error(t('errors.textDecryptFailed'))
      }
    }
    return { text: encryptName(source, key) }
  })

  // --- downloads -----------------------------------------------------------

  handle('downloads:list', () => ({ downloads: downloads.snapshot() }))

  handle('downloads:add', (_e, payload = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))

    const destDir = payload.destDir || storage.settings.download?.dir || ''
    if (!destDir) throw new Error(t('errors.needDownloadDir'))

    const download = downloads.add({
      mode: payload.mode,
      sessionId: payload.sessionId,
      path: payload.path,
      name: payload.name,
      destDir,
    })

    return { download, downloads: downloads.snapshot() }
  })

  handle('downloads:cancel', (_e, id) => ({ cancelled: downloads.cancel(id), downloads: downloads.snapshot() }))

  handle('downloads:clear', () => ({ downloads: downloads.clearFinished() }))

  handle('downloads:reveal', (_e, id) => {
    const job = downloads.snapshot().find((item) => item.id === id)
    if (!job?.outPath) throw new Error(t('errors.downloadIncomplete'))
    shell.showItemInFolder(job.outPath)
    return { revealed: true }
  })

  /** Drop the row from the finished list, keeping the downloaded file. */
  handle('downloads:forget', (_e, id) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    return { forgotten: downloads.forget(id), downloads: downloads.snapshot() }
  })

  /** Delete the downloaded file too. */
  handle('downloads:deleteFile', async (_e, id) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    const removed = await downloads.deleteFile(id)
    return { removed, downloads: downloads.snapshot() }
  })

  // --- uploads -------------------------------------------------------------

  handle('uploads:list', () => ({ uploads: uploads.snapshot() }))

  /**
   * Turn paths dropped onto the window into upload candidates.
   *
   * A drop hands the renderer `File` objects and nothing else — no size, no
   * check that the thing still exists. Both are done here, and anything that
   * vanished or turned out to be a folder is simply left out of the batch.
   */
  handle('uploads:prepare', async (_e, paths = []) => {
    const files = []
    for (const value of Array.isArray(paths) ? paths : []) {
      if (typeof value !== 'string' || !value) continue
      try {
        const stat = await fsp.stat(value)
        if (!stat.isFile()) continue
        files.push({ path: value, name: path.basename(value), size: stat.size })
      } catch {
        // Gone, or not readable: the rest of the drop is still worth uploading.
      }
    }
    return { files }
  })

  handle('uploads:add', (_e, payload = {}) => {
    // The gate covers writes too: an unlocked folder is not an invitation to
    // push files into it.
    requireUnlocked()
    return { ...uploads.add(payload), uploads: uploads.snapshot() }
  })

  handle('uploads:cancel', (_e, id) => ({ cancelled: uploads.cancel(id), uploads: uploads.snapshot() }))

  handle('uploads:clear', () => ({ uploads: uploads.clearFinished() }))

  /** Drop the row from the finished list, keeping the file in the cloud. */
  handle('uploads:forget', (_e, id) => {
    requireUnlocked()
    return { forgotten: uploads.forget(id), uploads: uploads.snapshot() }
  })

  /**
   * Delete the uploaded file from the cloud, then its row.
   *
   * The row is all that is left of that upload, so the account is signed in
   * again from the stored profile: the browser session that sent the file is
   * long gone, and the queue keeps no credentials.
   */
  handle('uploads:deleteRemote', async (_e, id) => {
    requireUnlocked()
    const job = uploads.snapshot().find((item) => item.id === id)
    if (!job) throw new Error(t('errors.uploadGone'))
    if (job.status !== 'done' || !job.remoteName || !job.profileId) {
      // Rows from before this was recorded have nothing to address the file by.
      throw new Error(t('errors.uploadNotOnCloud'))
    }

    const { profiles } = storage.listProfiles()
    const profile = profiles.find((item) => item.id === job.profileId)
    if (!profile) throw new Error(t('errors.profileMissing'))
    const secrets = storage.getSecrets(job.profileId) || {}

    const opened = await cloud.login({
      profile,
      password: secrets.password,
      skePassword: secrets.skePassword,
    })
    try {
      await cloud.removeUpload(opened.sessionId, job.dir, job.remoteName)
    } finally {
      // A delete does not need the session to outlive it.
      cloud.logout(opened.sessionId)
    }

    uploads.forget(id)
    return { uploads: uploads.snapshot() }
  })

  // --- theme ---------------------------------------------------------------

  handle('theme:broadcast', (event, theme) => {
    const value = theme === 'light' ? 'light' : 'dark'
    for (const win of BrowserWindow.getAllWindows()) {
      if (!win.isDestroyed() && win.webContents.id !== event.sender.id) {
        win.webContents.send('theme:changed', value)
      }
    }
    return { theme: value }
  })

  // --- locale --------------------------------------------------------------

  /**
   * Repaint every other window in the new language.
   *
   * `settings:patch` already applied the switch inside this process, so the
   * second `applyLanguage` is a no-op there; keeping it makes the channel
   * correct on its own, and the tray is rebuilt for the same reason the theme
   * is broadcast at all — a native menu cannot re-read a string it captured.
   */
  handle('locale:broadcast', (event, language) => {
    applyLanguage(language, app.getLocale())
    onLocaleChange?.()
    const code = currentLocale()
    for (const win of BrowserWindow.getAllWindows()) {
      if (!win.isDestroyed() && win.webContents.id !== event.sender.id) {
        win.webContents.send('locale:changed', code)
      }
    }
    return { locale: code }
  })

  // --- windows -------------------------------------------------------------

  handle('window:new', (_e, route) => {
    const created = openWindow?.(typeof route === 'string' ? route : '')
    return { created: !!created }
  })

  handle('window:close', (event) => {
    BrowserWindow.fromWebContents(event.sender)?.close()
    return { closed: true }
  })

  /**
   * Window chrome state for the custom title bar.
   *
   * The renderer polls this on resize instead of listening to main-process
   * events: a maximise triggered by double-clicking the drag region is a system
   * gesture, so the size change is the one signal that always arrives.
   */
  handle('window:state', (event) => {
    const win = BrowserWindow.fromWebContents(event.sender)
    if (!win) return { maximized: false, fullScreen: false }
    return { maximized: win.isMaximized(), fullScreen: win.isFullScreen() }
  })

  handle('window:minimize', (event) => {
    BrowserWindow.fromWebContents(event.sender)?.minimize()
    return { minimized: true }
  })

  handle('window:toggleMaximize', (event) => {
    const win = BrowserWindow.fromWebContents(event.sender)
    if (!win) throw new Error(t('errors.windowClosed'))
    if (win.isMaximized()) win.unmaximize()
    else win.maximize()
    return { maximized: win.isMaximized() }
  })

  handle('window:alwaysOnTop', (event, flag) => {
    const win = BrowserWindow.fromWebContents(event.sender)
    if (!win) throw new Error(t('errors.windowClosed'))
    // 'floating' is the macOS level that sits above normal windows; Windows
    // ignores the level argument, so one call covers both platforms.
    win.setAlwaysOnTop(!!flag, 'floating')
    return { onTop: win.isAlwaysOnTop() }
  })

  handle('window:toggleFullScreen', (event) => {
    const win = BrowserWindow.fromWebContents(event.sender)
    if (!win) throw new Error(t('errors.windowClosed'))
    win.setFullScreen(!win.isFullScreen())
    return { fullScreen: win.isFullScreen() }
  })

  /**
   * Shape the window around comic spreads: the pages then fill it instead of
   * sitting in black letterbox bars. `aspect` is the width/height ratio of the
   * page area, `chrome` the height of the in-window bar that is not page area.
   *
   * Only the width is kept; height follows the ratio, clamped to the display's
   * work area so the window cannot grow off-screen. A window the user owns
   * (maximised or full screen) is left alone.
   */
  handle('window:fitAspect', (event, aspect, chrome = 0) => {
    const win = BrowserWindow.fromWebContents(event.sender)
    if (!win) throw new Error(t('errors.windowClosed'))
    const ratio = Number(aspect)
    if (!Number.isFinite(ratio) || ratio <= 0) return { resized: false }
    if (win.isMaximized() || win.isFullScreen()) return { resized: false }

    const area = screen.getDisplayMatching(win.getBounds()).workArea
    const extra = Math.max(0, Math.round(Number(chrome) || 0))
    const [currentWidth] = win.getContentSize()
    let width = Math.min(currentWidth, area.width)
    let height = Math.round(width / ratio) + extra
    if (height > area.height) {
      height = area.height
      width = Math.max(320, Math.round((height - extra) * ratio))
    }

    win.setContentSize(width, height)
    const bounds = win.getBounds()
    win.setPosition(
      Math.min(Math.max(bounds.x, area.x), area.x + area.width - bounds.width),
      Math.min(Math.max(bounds.y, area.y), area.y + area.height - bounds.height),
    )
    return { resized: true, width, height }
  })

  // --- player window -------------------------------------------------------

  handle('player:openWindow', (event, payload = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error(t('errors.locked'))
    if (!payload?.item) throw new Error(t('errors.playerItemMissing'))
    // The opening renderer is remembered: the music window can route its
    // commands back to the shell that owns the audio element.
    return { opened: !!openStage?.(payload, event.sender.id) }
  })

  handle('player:payload', (event) => ({
    payload: takeStagePayload?.(event.sender.id) ?? null,
  }))

  // Stops the track, so the music window has nothing left to drive. The video
  // window is a different one and is left alone.
  handle('player:closeWindow', () => {
    closeMusicStage?.()
    return { closed: true }
  })

  /** Fire-and-forget mirror of the shell transport for every music window. */
  ipcMain.on('player:state', (event, state) => {
    for (const win of stageWindows?.() || []) {
      if (win.isDestroyed()) continue
      if (win.webContents.id === event.sender.id) continue
      win.webContents.send('player:state', state)
    }
  })

  /** Music window → shell: one transport action (toggle, seek, next, sync…). */
  handle('player:command', (event, command = {}) => {
    const owner = stageOwnerOf?.(event.sender.id) || mainWindow()
    if (!owner || owner.isDestroyed()) {
      console.warn('[player] no window owns this player, so the command cannot be forwarded')
      throw new Error(t('errors.playerHostMissing'))
    }
    owner.send('player:command', command)
    return {}
  })

  /**
   * Play a decrypted stream in an external player.
   *
   * The URL is checked against this launch's own origin: this channel starts a
   * process, so it must never double as "open whatever the renderer likes".
   */
  handle('player:external', (_e, { url, exe } = {}) => {
    const server = serverInfo()
    if (!server) throw new Error(t('errors.serverNotReady'))
    if (typeof url !== 'string' || !url.startsWith(`${server.origin}/media/`)) {
      throw new Error(t('errors.externalOnly'))
    }
    // The submenu picks one for this file; otherwise the configured player,
    // otherwise whatever detection found first. A choice made by hand is strict:
    // silently swapping in another player would hide a stale path.
    const chosen = typeof exe === 'string' ? exe.trim() : ''
    return playExternal(url, chosen || storage.settings.playback?.externalPlayer, {
      strict: !!chosen,
    })
  })

  /** Candidates for the "外部播放器" submenu. */
  handle('player:externalList', async () => ({
    players: await listPlayers(storage.settings.playback?.externalPlayer),
  }))

  // --- clipboard -----------------------------------------------------------

  /**
   * Copy a decrypted stream URL.
   *
   * That loopback URL is the only way an external player (mpv, VLC, PotPlayer)
   * can open a `.ske` without the plaintext ever touching the disk, which is
   * exactly what the media router is built for. Its lifetime is the app's: the
   * token is per launch and the media session closes with the track.
   */
  handle('clipboard:write', (_event, text) => {
    const value = typeof text === 'string' ? text.trim() : ''
    if (!value) throw new Error(t('errors.nothingToCopy'))
    clipboard.writeText(value)
    return {}
  })

  // --- caches --------------------------------------------------------------

  /**
   * Live usage of the caches.
   *
   * The decrypted payloads are per login and never written to disk; the only
   * on-disk part is the encrypted preview envelope, counted separately as
   * `thumbDisk`. Nothing here needs a password to clear.
   * The renderer adds its own counters (preview URLs, tile state) on top.
   */
  handle('cache:stats', () => {
    const media = registry.stats()
    const links = cloud.stats()

    return {
      previews: links.previews,
      previewBytes: links.previewBytes,
      thumbDisk: links.thumbDisk.count,
      thumbDiskBytes: links.thumbDisk.bytes,
      links: links.links,
      sessions: media.sessions,
      blocks: media.blocks,
      blockBytes: media.bytes,
    }
  })

  handle('cache:clear', async (_e, { target } = {}) => {
    // "All" deliberately leaves sessions open: closing them cuts whatever stream
    // is reading from them, which the user asks for separately.
    if (target === 'previews' || target === 'all') await cloud.clearPreviews()
    if (target === 'links' || target === 'all') cloud.clearLinks()
    if (target === 'blocks' || target === 'all') registry.clearBlocks()
    if (target === 'sessions') await registry.closeAll()
    return { cleared: target || 'all' }
  })

  /**
   * Close every stream a renderer opened.
   *
   * Called when a window is destroyed: unmount hooks do not run in that case, so
   * without this the registry would keep decrypting blocks for a window nobody
   * can see.
   */
  return {
    async releaseMediaFor(webContentsId) {
      const ids = [...mediaOwners]
        .filter(([, owner]) => owner === webContentsId)
        .map(([id]) => id)

      for (const id of ids) {
        mediaOwners.delete(id)
        await registry.close(id).catch(() => {})
      }
    },
  }
}
