/**
 * The built-in server as the rest of the app sees it.
 *
 * Owns the lifecycle (download → start → keep the matching cloud profile in
 * sync) and is the only module the IPC layer talks to. Two rules keep it
 * predictable:
 *
 *  - Turning the server off never deletes anything. The binary and the database
 *    stay on disk so flipping the switch back on is instant; removal is a
 *    separate, explicit action.
 *  - The server is private to this machine. It binds 127.0.0.1 on a port the
 *    user picks, and its admin password is generated here and sealed with the OS
 *    keychain — never typed by the user, never sent anywhere.
 */
import { shell } from 'electron'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { BACKENDS, DEFAULT_BACKEND, normalizeBackend } from './backends.js'
import { backendDir, install, installed, uninstall } from './download.js'
import { EmbeddedServer, randomPassword } from './server.js'

const MIN_PORT = 1024
const MAX_PORT = 65535

export class EmbeddedService {
  /**
   * @param {object} options
   * @param {import('../storage.js').Storage} options.storage
   * @param {(state: object) => void} [options.onUpdate] broadcast to every window
   */
  constructor({ storage, onUpdate }) {
    this.storage = storage
    this.onUpdate = onUpdate || (() => {})
    this.server = null
    this.status = 'stopped'
    this.error = ''
    this.progress = null
    this.busy = false
    /** Short-lived cache for the on-disk install record (see `#installed`). */
    this.installCache = null
  }

  get settings() {
    return this.storage.settings.embedded
  }

  get backendId() {
    return normalizeBackend(this.settings.backend).id
  }

  get origin() {
    return this.server?.running ? this.server.origin : ''
  }

  // --- state ---------------------------------------------------------------

  async snapshot() {
    const backend = normalizeBackend(this.settings.backend)
    const record = await this.#installed(backend.id)
    return {
      enabled: !!this.settings.enabled,
      backend: backend.id,
      backendLabel: backend.label,
      port: Number(this.settings.port) || 5244,
      version: this.settings.version || 'latest',
      mirror: this.settings.mirror || '',
      status: this.status,
      error: this.error,
      progress: this.progress,
      installed: record ? { tag: record.tag, size: record.size, installedAt: record.installedAt } : null,
      username: this.server?.username || '',
      origin: this.origin,
      backends: Object.values(BACKENDS).map(({ id, label, blurb }) => ({ id, label, blurb })),
      minPort: MIN_PORT,
      maxPort: MAX_PORT,
    }
  }

  #emit() {
    this.snapshot()
      .then((state) => this.onUpdate(state))
      .catch(() => {})
  }

  #setStatus(status, error = '') {
    this.status = status
    this.error = error
    this.#emit()
  }

  // --- lifecycle -----------------------------------------------------------

  /** Start from the saved settings, at launch. Failures are non-fatal. */
  async restore() {
    if (!this.settings.enabled) {
      this.#setStatus('stopped')
      return
    }
    try {
      await this.enable()
    } catch (err) {
      // Keep `enabled` true so the switch stays where the user put it; the page
      // shows the error and offers a retry.
      this.#setStatus('error', err.message)
    }
  }

  /**
   * Download (when needed), start, and point the cloud profile at the result.
   * @param {string} [backendId] switch backend while starting
   */
  async enable(backendId) {
    if (this.busy) throw new Error('内置服务器正在处理上一条操作，请稍候')
    this.busy = true
    try {
      const backend = normalizeBackend(backendId || this.settings.backend)
      if (this.server && this.server.backend.id !== backend.id) {
        await this.#stopServer()
      }
      if (this.settings.backend !== backend.id) {
        await this.storage.patchSettings({ embedded: { backend: backend.id } })
      }
      if (this.settings.enabled !== true) {
        await this.storage.patchSettings({ embedded: { enabled: true } })
      }

      const record = await this.#ensureInstalled(backend.id)

      this.#setStatus('starting')
      const server = new EmbeddedServer({
        backend,
        dir: backendDir(backend.id),
        binary: record.path,
        port: Number(this.settings.port) || 5244,
        onExit: (code) => this.#onServerExit(server, code),
      })
      await server.start()
      this.server = server

      await this.#syncProfile(backend, server)
      await this.storage.patchSettings({ embedded: { installedTag: record.tag } })
      this.progress = null
      this.#setStatus('running')
      return this.snapshot()
    } catch (err) {
      this.#setStatus('error', err.message)
      throw err
    } finally {
      this.busy = false
    }
  }

  async disable() {
    if (this.busy) return
    this.busy = true
    try {
      await this.#stopServer()
      await this.storage.patchSettings({ embedded: { enabled: false } })
      this.progress = null
      this.#setStatus('stopped')
    } finally {
      this.busy = false
    }
  }

  /** Re-download whatever the version setting currently points at. */
  async reinstall(backendId) {
    const backend = normalizeBackend(backendId || this.settings.backend)
    const wasEnabled = this.settings.enabled
    await this.#stopServer()
    this.installCache = null
    await fsp.rm(path.join(backendDir(backend.id), 'version.json'), { force: true })
    if (wasEnabled) return this.enable(backend.id)
    this.#setStatus('stopped')
    return this.snapshot()
  }

  /** Switch backend. A running server is restarted on the other one. */
  async setBackend(backendId) {
    const backend = normalizeBackend(backendId)
    if (this.server?.running) return this.enable(backend.id)
    await this.storage.patchSettings({ embedded: { backend: backend.id } })
    this.#emit()
    return this.snapshot()
  }

  async setPort(port) {
    const value = Number(port)
    if (!Number.isInteger(value) || value < MIN_PORT || value > MAX_PORT) {
      throw new Error(`端口需要在 ${MIN_PORT}-${MAX_PORT} 之间`)
    }
    await this.storage.patchSettings({ embedded: { port: value } })
    if (this.server && this.server.running) {
      this.#setStatus('starting')
      try {
        await this.server.restart(value)
        await this.#syncProfile(this.server.backend, this.server)
        this.#setStatus('running')
      } catch (err) {
        this.#setStatus('error', err.message)
        throw err
      }
    }
    return this.snapshot()
  }

  async setVersion(version) {
    const value = String(version || 'latest').trim() || 'latest'
    await this.storage.patchSettings({ embedded: { version: value } })
    this.#emit()
    return this.snapshot()
  }

  async setMirror(mirror) {
    await this.storage.patchSettings({ embedded: { mirror: String(mirror || '').trim() } })
    this.#emit()
    return this.snapshot()
  }

  /** Called from `before-quit`; never throws. */
  async shutdown() {
    await this.#stopServer().catch(() => {})
  }

  // --- account -------------------------------------------------------------

  async account() {
    const server = this.#requireServer()
    return server.account()
  }

  /** @param {{ username?: string, password?: string }} patch */
  async updateAccount(patch = {}) {
    const server = this.#requireServer()
    const result = await server.updateAccount(patch)
    // The password is regenerated in the UI, so the profile has to follow or the
    // next connect would use stale credentials.
    await this.#syncProfile(server.backend, server)
    this.#emit()
    return result
  }

  /** Plaintext password, only for the "show / copy" button on this page. */
  async revealPassword() {
    const server = this.#requireServer()
    const credentials = await server.credentials()
    return credentials?.password || ''
  }

  regeneratePassword() {
    return randomPassword()
  }

  // --- storage -------------------------------------------------------------

  async storages() {
    return this.#requireServer().storages()
  }

  /** Names of every driver this build can mount. */
  async drivers() {
    return this.#requireServer().drivers()
  }

  /** Field definitions for one driver, used to build the add/edit form. */
  async driverInfo(driver) {
    if (!driver) throw new Error('缺少驱动名')
    return this.#requireServer().driverInfo(driver)
  }

  async createStorage(payload) {
    await this.#requireServer().createStorage(payload)
    return this.storages()
  }

  async updateStorage(payload) {
    await this.#requireServer().updateStorage(payload)
    return this.storages()
  }

  async deleteStorage(id) {
    await this.#requireServer().deleteStorage(id)
    return this.storages()
  }

  async setStorageEnabled(id, enabled) {
    await this.#requireServer().setStorageEnabled(id, enabled)
    return this.storages()
  }

  // --- misc ----------------------------------------------------------------

  logs() {
    return this.server?.logsTail(150) || ''
  }

  async openDataDir() {
    const dir = backendDir(this.backendId)
    await fsp.mkdir(dir, { recursive: true })
    await shell.openPath(dir)
  }

  /** Delete the binary and the database, plus the profile that pointed at it. */
  async uninstall(backendId) {
    const backend = normalizeBackend(backendId || this.settings.backend)
    await this.disable()
    await uninstall(backend.id)
    this.installCache = null
    await this.storage.patchSettings({ embedded: { enabled: false, installedTag: '' } })
    const { profiles } = this.storage.listProfiles()
    const stale = profiles.find((profile) => profile.embedded)
    if (stale) await this.storage.deleteProfile(stale.id)
    this.#setStatus('stopped')
    return this.snapshot()
  }

  // --- internals -----------------------------------------------------------

  #requireServer() {
    if (!this.server?.running) throw new Error('内置服务器未在运行，请先在设置中开启')
    return this.server
  }

  /**
   * Cached `installed()` probe.
   *
   * `snapshot()` runs on every progress tick while a download is in flight, and
   * hitting the disk 7 times a second to answer a question whose answer cannot
   * change during that time is pure waste.
   */
  async #installed(backendId) {
    const now = Date.now()
    if (
      this.installCache &&
      this.installCache.backend === backendId &&
      now - this.installCache.at < 1500
    ) {
      return this.installCache.record
    }
    const record = await installed(backendId)
    this.installCache = { backend: backendId, at: now, record }
    return record
  }

  async #stopServer() {
    if (!this.server) return
    const server = this.server
    this.server = null
    await server.stop()
    server.dispose()
  }

  /**
   * The child process died without being asked to.
   *
   * This used to be silent: the service kept reporting `running` for a process
   * that no longer existed, so every call afterwards failed with Node's bare
   * `fetch failed` and the page still looked healthy. AList does crash on some
   * machines, so the status has to say so.
   *
   * `this.server !== server` means a restart already replaced it.
   */
  #onServerExit(server, code) {
    if (this.server !== server) return
    this.server = null
    this.progress = null
    this.#setStatus(
      'error',
      `${server.backend.label} 意外退出了（退出码 ${code}）。点“重试”可以重新启动；若是反复退出，可以换一个后端或重新安装它。`,
    )
  }

  /**
   * Make sure the requested build is on disk.
   *
   * "latest" is only fetched when nothing is installed: an app restart must not
   * turn into a 40 MB download every time. A pinned tag re-downloads whenever it
   * differs from what is installed, which is what the version field promises.
   */
  async #ensureInstalled(backendId) {
    const wanted = String(this.settings.version || 'latest').trim()
    const record = await installed(backendId)
    if (record && (wanted === 'latest' || record.tag === wanted)) return record

    this.#setStatus('downloading')
    const mirror = String(this.settings.mirror || '').trim()
    const fresh = await install(backendId, {
      version: wanted,
      mirrors: mirror ? [mirror] : [],
      onProgress: (progress) => {
        this.progress = progress
        this.#emit()
      },
    })
    this.progress = null
    return fresh
  }

  async #syncProfile(backend, server) {
    const credentials = await server.credentials()
    if (!credentials?.password) throw new Error('内置服务器的账号密码缺失')

    // Take over as the active profile only when the user has not picked another
    // one: starting the local server must not yank them out of a remote library
    // they are already browsing.
    const { activeId, profiles } = this.storage.vault
    const current = profiles.find((profile) => profile.embedded)
    const activate = !activeId || activeId === current?.id

    return this.storage.ensureEmbeddedProfile({
      backend: backend.id,
      server: server.origin,
      username: credentials.username,
      password: credentials.password,
      activate,
    })
  }
}
