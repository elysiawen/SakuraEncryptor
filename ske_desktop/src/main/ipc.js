/**
 * All IPC channels in one place.
 *
 * Every handler returns `{ ok: true, ...payload }` or `{ ok: false, error }`
 * instead of throwing: Electron wraps thrown errors in a message that hides the
 * real cause, and the UI needs to show the cause verbatim.
 */
import { BrowserWindow, app, clipboard, dialog, ipcMain, shell } from 'electron'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { listPlayers, playExternal } from './external-player.js'
import { deriveNameKey } from './ske-names.js'

function handle(channel, fn) {
  ipcMain.handle(channel, async (event, ...args) => {
    try {
      const result = await fn(event, ...args)
      return { ok: true, ...(result ?? {}) }
    } catch (err) {
      return { ok: false, error: err?.message || String(err) }
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
 * @param {() => { origin: string, token: string } | null} deps.serverInfo
 * @param {() => import('electron').BrowserWindow | null} deps.mainWindow
 * @param {(route?: string) => import('electron').BrowserWindow | null} [deps.openWindow]
 * @param {(payload: object) => boolean} [deps.openStage]
 * @param {(webContentsId: number) => object | null} [deps.takeStagePayload]
 * @param {() => import('electron').BrowserWindow[]} [deps.stageWindows]
 * @param {(webContentsId: number) => import('electron').WebContents | null} [deps.stageOwnerOf]
 * @param {() => void} [deps.closeMusicStage]
 * @param {() => void} [deps.onLock]
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
  serverInfo,
  mainWindow,
  openWindow,
  openStage,
  takeStagePayload,
  stageWindows,
  stageOwnerOf,
  closeMusicStage,
  onLock,
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
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')
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

  handle('settings:get', () => ({ settings: storage.settings }))

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
    return { settings }
  })

  // --- dialogs -------------------------------------------------------------

  /** Pick a container to *decrypt*: the `.ske` filter belongs here and only here. */
  handle('dialog:pickFile', async () => {
    const win = mainWindow()
    if (!win) return { path: null }
    const result = await dialog.showOpenDialog(win, {
      title: '选择 .ske 文件',
      properties: ['openFile'],
      filters: [
        { name: 'Sakura Encryptor 容器', extensions: ['ske'] },
        { name: '所有文件', extensions: ['*'] },
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
      title: '选择要加密的文件',
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: '所有文件', extensions: ['*'] }],
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
      title: '选择要上传的文件',
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: '所有文件', extensions: ['*'] }],
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
      title: '选择外部播放器',
      properties: ['openFile'],
      filters:
        process.platform === 'win32'
          ? [{ name: '可执行文件', extensions: ['exe'] }, { name: '所有文件', extensions: ['*'] }]
          : [{ name: '所有文件', extensions: ['*'] }],
    })
    return { path: result.canceled ? null : result.filePaths[0] }
  })

  handle('dialog:pickDirectory', async () => {
    const win = mainWindow()
    if (!win) return { path: null }
    const result = await dialog.showOpenDialog(win, {
      title: '选择文件夹',
      properties: ['openDirectory'],
    })
    return { path: result.canceled ? null : result.filePaths[0] }
  })

  // --- cloud ---------------------------------------------------------------

  handle('cloud:connect', async (_e, profileId) => {
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')

    const { profiles } = storage.listProfiles()
    const profile = profiles.find((p) => p.id === profileId)
    if (!profile) throw new Error('配置不存在')

    // Secrets are unsealed here and handed straight to CloudService; they never
    // travel back through IPC.
    const secrets = storage.getSecrets(profileId) || {}
    const result = await cloud.login({
      profile,
      password: secrets.password,
      skePassword: secrets.skePassword,
    })
    return { session: result }
  })

  handle('cloud:status', (_e, sessionId) => ({ status: cloud.status(sessionId) }))

  handle('cloud:disconnect', (_e, sessionId) => {
    cloud.logout(sessionId)
    return { disconnected: true }
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
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')
    return cloud.makeDir(sessionId, path, name, { encrypt })
  })

  handle('cloud:rename', (_e, { sessionId, path, newName } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')
    return cloud.rename(sessionId, path, newName)
  })

  handle('cloud:remove', (_e, { sessionId, path } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')
    return cloud.remove(sessionId, path)
  })

  // --- media ---------------------------------------------------------------

  handle('media:open', async (event, payload = {}) => {
    if (security.isEnabled() && !security.unlocked) {
      throw new Error('应用已锁定')
    }
    const server = serverInfo()
    if (!server) throw new Error('本地服务尚未就绪')

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
      throw new Error('应用已锁定')
    }
    const server = serverInfo()
    if (!server) throw new Error('本地服务尚未就绪')

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
      if (!localLibrary.unlocked) throw new Error('本地库尚未解锁')
      target = localLibrary.filePath(path)
      password = localLibrary.password
    } else {
      const session = cloud.get(sessionId)
      if (!session) throw new Error('云端会话已断开，请重新连接')
      target = (await cloud.resolve(sessionId, path)).rawUrl
      password = session.skePassword
    }

    const info = await registry.open({ target, password, name: name || path })
    try {
      if (info.size > MAX_SUBTITLE_BYTES) throw new Error('文本文件超过 2 MB，已跳过')
      if (info.size === 0) throw new Error('文件内容为空')

      const opened = registry.get(info.id)
      const chunks = []
      for await (const chunk of opened.readRange(0, info.size - 1)) chunks.push(Buffer.from(chunk))

      return { text: decodeSubtitleBuffer(Buffer.concat(chunks)), size: info.size }
    } finally {
      await registry.close(info.id).catch(() => {})
    }
  }

  handle('subtitles:read', async (_e, { mode, path, name, sessionId } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')
    if (!path) throw new Error('缺少字幕文件')
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
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')
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
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')
    return localLibrary.makeDir(path, name, { encrypt })
  })

  handle('local:rename', (_e, { path, newName } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')
    return localLibrary.rename(path, newName)
  })

  handle('local:remove', (_e, { path } = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')
    return localLibrary.remove(path)
  })

  /**
   * Play a local container. The password stays here: the renderer only receives
   * a stream URL, same as the cloud path.
   */
  handle('local:play', async (event, { path, name }) => {
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')
    if (!localLibrary.unlocked) throw new Error('本地库尚未解锁')

    const server = serverInfo()
    if (!server) throw new Error('本地服务尚未就绪')

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
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')

    // An empty password field means "use the active profile's media password",
    // which is what most users expect when they only have one vault.
    const secret = password || activeSecrets()?.skePassword || ''
    if (!secret) throw new Error('请填写加密密码，或先配置一个带媒体密码的云端配置')

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

  // --- downloads -----------------------------------------------------------

  handle('downloads:list', () => ({ downloads: downloads.snapshot() }))

  handle('downloads:add', (_e, payload = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')

    const destDir = payload.destDir || storage.settings.download?.dir || ''
    if (!destDir) throw new Error('请先在设置里选择下载目录')

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
    if (!job?.outPath) throw new Error('文件尚未下载完成')
    shell.showItemInFolder(job.outPath)
    return { revealed: true }
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
    if (!win) throw new Error('窗口已关闭')
    if (win.isMaximized()) win.unmaximize()
    else win.maximize()
    return { maximized: win.isMaximized() }
  })

  handle('window:alwaysOnTop', (event, flag) => {
    const win = BrowserWindow.fromWebContents(event.sender)
    if (!win) throw new Error('窗口已关闭')
    // 'floating' is the macOS level that sits above normal windows; Windows
    // ignores the level argument, so one call covers both platforms.
    win.setAlwaysOnTop(!!flag, 'floating')
    return { onTop: win.isAlwaysOnTop() }
  })

  handle('window:toggleFullScreen', (event) => {
    const win = BrowserWindow.fromWebContents(event.sender)
    if (!win) throw new Error('窗口已关闭')
    win.setFullScreen(!win.isFullScreen())
    return { fullScreen: win.isFullScreen() }
  })

  // --- player window -------------------------------------------------------

  handle('player:openWindow', (event, payload = {}) => {
    if (security.isEnabled() && !security.unlocked) throw new Error('应用已锁定')
    if (!payload?.item) throw new Error('缺少要播放的文件')
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
      console.warn('[player] 找不到打开播放窗口的界面，指令无法转发')
      throw new Error('主窗口不可用，请重启应用')
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
    if (!server) throw new Error('本地服务尚未就绪')
    if (typeof url !== 'string' || !url.startsWith(`${server.origin}/media/`)) {
      throw new Error('只能把解密流交给外部播放器')
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
    if (!value) throw new Error('没有可复制的内容')
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
