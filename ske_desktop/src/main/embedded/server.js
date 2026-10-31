/**
 * One local server process, from a cold start to a usable HTTP API.
 *
 * The app runs AList or OpenList as a child process and then talks to it over
 * `127.0.0.1`, which means the whole cloud stack (`cloud.js`, the media router,
 * uploads, thumbnails) is reused untouched — it only ever sees a server address.
 *
 * Three things are deliberate:
 *
 *  - `config.json` is rewritten before every boot. Both projects default to
 *    `0.0.0.0:5244`, i.e. "serve the whole LAN"; only a fixed loopback address
 *    and an explicit port keep the server private to this machine.
 *  - Initialisation is discovered, not assumed. OpenList ships an install wizard
 *    (`/api/public/init_status` + `/init/setup`), AList instead prints a random
 *    password once on first boot. Both are handled, so following upstream's
 *    `latest` tag cannot lock a user out of their own server.
 *  - A fresh install gets one Local storage mounted at `/`, pointing into the
 *    app's own `files/` directory, so the server is useful before the user has
 *    configured anything.
 */
import { execFile, spawn } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import net from 'node:net'
import path from 'node:path'
import { sealSecret, unsealSecret } from '../storage.js'

const START_TIMEOUT_MS = 25_000
const PING_INTERVAL_MS = 300
const STOP_TIMEOUT_MS = 5_000
const LOG_LINES = 400
/** Rotate `server.log` past this size; a crash dump alone can be megabytes. */
const LOG_MAX_BYTES = 8 * 1024 * 1024

/** "Successfully created the admin user and the initial password is: xxxx" */
const INITIAL_PASSWORD_RE = /initial password is:\s*(\S+)/i
const ANSI_RE = /\u001b\[[0-9;]*m/g

const PASSWORD_ALPHABET = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'

export function randomPassword(length = 24) {
  let out = ''
  for (let i = 0; i < length; i += 1) out += PASSWORD_ALPHABET[crypto.randomInt(PASSWORD_ALPHABET.length)]
  return out
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** True when nothing is listening on the loopback port we want. */
export function portAvailable(port) {
  return new Promise((resolve) => {
    const probe = net.createServer()
    probe.once('error', () => resolve(false))
    probe.once('listening', () => probe.close(() => resolve(true)))
    probe.listen(port, '127.0.0.1')
  })
}

function normalizeMount(value) {
  const mount = String(value || '').trim()
  if (!mount) return ''
  const withSlash = mount.startsWith('/') ? mount : `/${mount}`
  return withSlash.length > 1 ? withSlash.replace(/\/+$/, '') : '/'
}

function storageRoot(addition) {
  try {
    const parsed = typeof addition === 'string' ? JSON.parse(addition) : addition
    return parsed?.root_folder_path || ''
  } catch {
    return ''
  }
}

/** Shape a raw storage row into what the settings page renders. */
function storageView(item) {
  return {
    id: item.id,
    mountPath: item.mount_path || item.mountPath || '',
    driver: item.driver || '',
    status: item.status || '',
    order: Number(item.order) || 0,
    rootPath: storageRoot(item.addition),
    /** The driver config as an object, so the edit form can prefill itself. */
    values: parseAddition(item.addition),
    readonly: item.readonly ?? item.read_only ?? false,
  }
}

function parseAddition(addition) {
  try {
    const parsed = typeof addition === 'string' ? JSON.parse(addition) : addition
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

/** Normalise the server's reflection output into something a form can render. */
function driverField(item) {
  const options = String(item?.options || '')
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean)
  return {
    name: String(item?.name || ''),
    type: String(item?.type || 'string').toLowerCase(),
    default: item?.default === undefined || item?.default === null ? '' : String(item.default),
    options,
    required: !!item?.required,
    help: String(item?.help || ''),
  }
}

/**
 * Normalise one driver's spec.
 *
 * Current builds answer `{ common, additional }`; older ones answer the
 * additional list directly. Both shapes are accepted, so an upstream reshuffle
 * degrades to a form with the driver's fields instead of an empty one.
 */
function normalizeDriverSpec(data) {
  const common = Array.isArray(data?.common) ? data.common : []
  const additional = Array.isArray(data?.additional)
    ? data.additional
    : Array.isArray(data)
      ? data
      : []
  return { common: common.map(driverField), additional: additional.map(driverField) }
}

/**
 * Turn one form value back into the JSON type the Go struct expects.
 *
 * Whole numbers must stay integers: an integral float is rejected by `int64`
 * fields ("can not decode float as int"), and the reflected "number" type does
 * not say which fields are ints.
 */
function coerceField(field, value) {
  const raw = value === undefined || value === null || value === '' ? field.default : value
  if (field.type === 'bool') {
    if (typeof raw === 'boolean') return raw
    return String(raw).toLowerCase() === 'true' || raw === '1'
  }
  // Drivers declare `number`, `float` and occasionally `int`; all of them are
  // numeric JSON, and a string would make the Go decoder reject the whole mount.
  if (field.type === 'number' || field.type === 'float' || field.type === 'int' || field.type === 'integer') {
    const num = Number(raw)
    return Number.isFinite(num) ? num : 0
  }
  return String(raw ?? '')
}

function additionFrom(fields, values = {}) {
  const addition = {}
  for (const field of fields) {
    const value = coerceField(field, values[field.name])
    if (field.required && field.type !== 'bool' && String(value).trim() === '') {
      throw new Error(`「${field.name}」不能为空${field.help ? `：${field.help}` : ''}`)
    }
    addition[field.name] = value
  }
  return addition
}

export class EmbeddedServer {
  /**
   * @param {object} options
   * @param {object} options.backend normalized entry from `backends.js`
   * @param {string} options.dir data directory owned by this server
   * @param {string} options.binary absolute path of the executable
   * @param {number} options.port loopback port to bind
   * @param {(code: number) => void} [options.onExit] the process died on its own
   */
  constructor({ backend, dir, binary, port, onExit }) {
    this.backend = backend
    this.dir = dir
    this.binary = binary
    this.port = port
    this.onExit = onExit || null

    this.child = null
    this.token = ''
    this.username = ''
    this.logs = []
    this.initialPassword = ''
    this.logStream = null
    this.pending = ''
  }

  get origin() {
    return `http://127.0.0.1:${this.port}`
  }

  get running() {
    return !!this.child && this.child.exitCode === null
  }

  // --- lifecycle -----------------------------------------------------------

  async start() {
    if (this.running) return

    await this.#ensureConfig()

    if (!(await portAvailable(this.port))) {
      throw new Error(
        `端口 ${this.port} 已被占用（可能本机已经跑着一个 AList/OpenList，或上次未正常退出），请在设置里换一个端口`,
      )
    }

    await this.#boot()
    try {
      await this.#waitReady()
      this.initialPassword = this.initialPassword || this.#scanLogsForPassword()
      const account = await this.#ensureAccount()
      this.username = account.username
      await this.#ensureStorage()
    } catch (err) {
      await this.stop()
      throw err
    }
  }

  async stop() {
    await this.#stopProcess()
    this.token = ''
  }

  /** Stop, rebind on `port`, start again. Used when the port setting changes. */
  async restart(port) {
    await this.stop()
    if (Number.isFinite(port) && port > 0 && port !== this.port) this.port = port
    // Windows releases the listener asynchronously; a short pause avoids a
    // spurious "port busy" when the user just changed the port.
    await delay(300)
    await this.start()
  }

  dispose() {
    this.logStream?.end()
    this.logStream = null
  }

  logsTail(count = 120) {
    return this.logs.slice(-count).join('\n')
  }

  // --- account -------------------------------------------------------------

  async account() {
    const me = await this.#api('/api/me')
    return { username: me?.data?.username || this.username }
  }

  /** The admin credentials this app generated, for the profile and the UI. */
  async credentials() {
    return this.#readCredentials()
  }

  /**
   * Rename the admin and/or change its password.
   *
   * Both backends expose `GET /api/me` and `POST /api/admin/user/update`; the
   * record is sent back exactly as received (AList reports `role` as an array,
   * OpenList as a number) so neither server sees a shape it does not expect.
   */
  async updateAccount({ username, password } = {}) {
    const me = (await this.#api('/api/me'))?.data
    if (!me) throw new Error('无法读取内置服务器账号')

    const saved = await this.#readCredentials()
    const nextName = String(username || me.username || 'admin').trim()
    const nextPassword = String(password || saved?.password || '')
    if (!nextName) throw new Error('账号不能为空')
    if (!nextPassword) throw new Error('缺少内置服务器密码')

    await this.#api('/api/admin/user/update', {
      method: 'POST',
      body: { ...me, username: nextName, password: nextPassword },
    })

    await this.#writeCredentials({ username: nextName, password: nextPassword })
    await this.login(nextName, nextPassword)
    this.username = nextName
    return { username: nextName, password: nextPassword }
  }

  // --- storage -------------------------------------------------------------

  async storages() {
    const res = await this.#api('/api/admin/storage/list')
    const items = Array.isArray(res?.data) ? res.data : res?.data?.content || []
    return items.map(storageView).sort((a, b) => a.order - b.order)
  }

  /**
   * Every driver this build can mount (Local, WebDav, S3, ...).
   *
   * `/driver/names` returns the name array; `/driver/list` returns a map of every
   * driver's full field spec, which is far too much to ship just to fill a
   * dropdown.
   */
  async drivers() {
    const res = await this.#api('/api/admin/driver/names')
    const names = Array.isArray(res?.data) ? res.data : []
    return names.map((name) => String(name)).sort((a, b) => a.localeCompare(b))
  }

  /**
   * The fields a driver declares.
   *
   * `common` holds the mount-level options the server fills in (order, cache,
   * webdav policy...), `additional` is the driver's own config. The server
   * reflects these off the Go struct, so the app never hard-codes a driver.
   */
  async driverInfo(driver) {
    const res = await this.#api(`/api/admin/driver/info?driver=${encodeURIComponent(driver)}`)
    const spec = normalizeDriverSpec(res?.data)
    if (spec.common.length || spec.additional.length) return spec
    // A build that answers `index.html` for this route (or lists nothing) still
    // ships every spec in `/driver/list`; one extra request beats a form the
    // user cannot fill in.
    return this.#driverSpecFromList(driver)
  }

  /** Fallback for `driverInfo`: `/driver/list` is `{ [driver]: spec }`. */
  async #driverSpecFromList(driver) {
    try {
      const res = await this.#api('/api/admin/driver/list')
      return normalizeDriverSpec(res?.data?.[driver])
    } catch {
      return { common: [], additional: [] }
    }
  }

  /**
   * Mount anything the server supports.
   *
   * `values` is keyed by the driver's own field names and already contains the
   * user's edits; anything missing falls back to the driver default.
   */
  async createStorage({ driver = 'Local', mountPath, values = {}, cacheExpiration = 30 } = {}) {
    const mount = normalizeMount(mountPath)
    if (!mount) throw new Error('挂载路径不能为空')

    const info = await this.driverInfo(driver)
    const body = {
      driver,
      mount_path: mount,
      status: 'work',
      addition: JSON.stringify(additionFrom(info.additional, values)),
      cache_expiration: Number(cacheExpiration) || 30,
    }
    // The mount-level options are not part of `addition`; the server reads them
    // from the top level, so seed them from the defaults it advertises.
    for (const field of info.common) {
      if (field.name === 'mount_path') continue
      body[field.name] = coerceField(field, undefined)
    }

    await this.#api('/api/admin/storage/create', { method: 'POST', body })
  }

  async updateStorage({ id, mountPath, values } = {}) {
    if (!id) throw new Error('缺少存储 id')
    const res = await this.#api('/api/admin/storage/list')
    const items = Array.isArray(res?.data) ? res.data : res?.data?.content || []
    const current = items.find((item) => String(item.id) === String(id))
    if (!current) throw new Error('存储不存在')

    const body = { ...current }
    if (mountPath !== undefined) body.mount_path = normalizeMount(mountPath)
    if (values) {
      const info = await this.driverInfo(current.driver)
      body.addition = JSON.stringify(additionFrom(info.additional, values))
    }
    await this.#api('/api/admin/storage/update', { method: 'POST', body })
  }

  async deleteStorage(id) {
    if (!id) throw new Error('缺少存储 id')
    await this.#storageAction('delete', id)
  }

  /**
   * Park a mount without deleting it.
   *
   * A disabled storage disappears from listings but keeps its files and its
   * configuration, which is what "hide this folder for now" should mean.
   */
  async setStorageEnabled(id, enabled) {
    if (!id) throw new Error('缺少存储 id')
    await this.#storageAction(enabled ? 'enable' : 'disable', id)
  }

  /**
   * delete / enable / disable bind `id` from the query string
   * (`server/handles/storage.go`), while some older builds bind it from the JSON
   * body. Try the documented form first and fall back once, so neither backend
   * version turns a working button into a 400.
   */
  async #storageAction(action, id) {
    try {
      await this.#api(`/api/admin/storage/${action}?id=${encodeURIComponent(id)}`, { method: 'POST' })
    } catch (err) {
      if (err.status !== 400) throw err
      await this.#api(`/api/admin/storage/${action}`, { method: 'POST', body: { id: Number(id) || id } })
    }
  }

  // --- HTTP ----------------------------------------------------------------

  async login(username, password) {
    const res = await this.#api('/api/auth/login', { method: 'POST', body: { username, password } })
    const token = res?.data?.token
    if (!token) throw new Error(`${this.backend.label} 登录失败`)
    this.token = token
    return token
  }

  async #api(pathname, { method = 'GET', body, token = this.token, timeout = 20_000 } = {}) {
    const res = await fetch(`${this.origin}${pathname}`, {
      method,
      headers: {
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: token } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeout),
    })

    const text = await res.text()
    let json = null
    try {
      json = JSON.parse(text)
    } catch {
      // A HTML body means the route does not exist on this build: the SPA
      // fallback answers 200 with index.html.
    }

    if (!res.ok) {
      const err = new Error(json?.message || `HTTP ${res.status}`)
      err.status = res.status
      throw err
    }
    if (json && typeof json.code === 'number' && json.code !== 200) {
      throw new Error(json.message || '请求失败')
    }
    return json ?? {}
  }

  // --- internals -----------------------------------------------------------

  async #ensureConfig() {
    await fsp.mkdir(this.dir, { recursive: true })
    const file = path.join(this.dir, 'config.json')
    let config = {}
    try {
      config = JSON.parse(await fsp.readFile(file, 'utf8'))
    } catch {
      config = {}
    }

    const scheme = config.scheme && typeof config.scheme === 'object' ? config.scheme : {}
    // Loopback only. Upstream defaults to 0.0.0.0, which would expose the whole
    // server — including every mounted storage — to the local network.
    scheme.address = '127.0.0.1'
    scheme.http_port = this.port
    scheme.https_port = -1
    scheme.force_https = false
    config.scheme = scheme

    config.temp_dir = path.join(this.dir, 'temp')
    config.bleve_dir = path.join(this.dir, 'bleve')
    // An empty CDN keeps startup independent of the network.
    config.cdn = ''

    await fsp.writeFile(file, JSON.stringify(config, null, 2), 'utf8')
  }

  async #boot() {
    this.#openLog()
    this.pending = ''
    this.initialPassword = ''

    const child = spawn(this.binary, ['server', '--data', this.dir], {
      cwd: this.dir,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    this.child = child

    child.stdout?.setEncoding('utf8')
    child.stderr?.setEncoding('utf8')
    child.stdout?.on('data', (chunk) => this.#appendLog(chunk))
    child.stderr?.on('data', (chunk) => this.#appendLog(chunk))
    child.on('error', (err) => this.#appendLog(`[ske] 启动失败：${err.message}\n`))
    child.on('exit', (code) => {
      this.#appendLog(`[ske] ${this.backend.label} 已退出（code ${code}）\n`)
      // `#stopProcess` clears `this.child` before killing, so a live reference
      // here means the process died on its own. That is a crash, and silence
      // about it left the UI reporting a server that no longer existed.
      const unexpected = this.child === child
      if (unexpected) this.child = null
      if (unexpected) this.onExit?.(code)
    })
  }

  async #stopProcess() {
    const child = this.child
    if (!child) return
    this.child = null

    await new Promise((resolve) => {
      let settled = false
      const finish = () => {
        if (settled) return
        settled = true
        resolve()
      }
      child.once('exit', finish)
      try {
        // POSIX gets a real SIGTERM so the Go side checkpoints SQLite (WAL);
        // Windows has no such signal and takes the TerminateProcess path — the
        // database recovers from a leftover WAL on the next boot.
        child.kill(process.platform === 'win32' ? undefined : 'SIGTERM')
      } catch {
        finish()
      }
      setTimeout(() => {
        try {
          child.kill('SIGKILL')
        } catch {
          /* already gone */
        }
        finish()
      }, STOP_TIMEOUT_MS)
    })
  }

  async #waitReady() {
    const deadline = Date.now() + START_TIMEOUT_MS
    while (Date.now() < deadline) {
      if (!this.running) {
        throw new Error(`${this.backend.label} 启动后立即退出：${this.logsTail(12) || '没有输出'}`)
      }
      try {
        const res = await fetch(`${this.origin}/ping`, { signal: AbortSignal.timeout(1500) })
        if (res.ok) return
      } catch {
        // Not listening yet.
      }
      await delay(PING_INTERVAL_MS)
    }
    throw new Error(`${this.backend.label} 启动超时：${this.logsTail(12) || '没有输出'}`)
  }

  async #ensureAccount() {
    const saved = await this.#readCredentials()
    if (saved) {
      try {
        await this.login(saved.username, saved.password)
        return saved
      } catch {
        // The database may have been reset or the password changed outside the
        // app; fall through to the recovery paths below.
      }
    }

    // 1. An install wizard, if this build has one. A 404 (or an HTML fallback)
    //    means it does not: AList answers index.html for unknown /api paths.
    let wizard = null
    try {
      const status = await this.#api('/api/public/init_status')
      const flag = status?.data?.initialized
      wizard = flag === false ? 'needed' : flag === true ? 'ready' : null
    } catch (err) {
      if (err.status !== 404) throw err
    }

    if (wizard === 'needed') {
      const account = { username: 'admin', password: randomPassword() }
      await this.#api('/api/public/init/setup', {
        method: 'POST',
        body: { username: account.username, password: account.password },
      })
      await this.login(account.username, account.password)
      await this.#writeCredentials(account)
      return account
    }

    // 2. No wizard: the first boot printed a random password exactly once.
    const printed = this.initialPassword || this.#scanLogsForPassword()
    if (printed) {
      const account = { username: 'admin', password: printed }
      await this.login(account.username, account.password)
      await this.#writeCredentials(account)
      return account
    }

    // 3. An existing database whose password we never learned. Resetting it
    //    through the CLI only works because the admin row already exists, which
    //    is exactly the situation here.
    const account = { username: 'admin', password: randomPassword() }
    await this.#stopProcess()
    await this.#runCli(['admin', 'set', account.password, '--data', this.dir])
    await this.#boot()
    await this.#waitReady()
    await this.login(account.username, account.password)
    await this.#writeCredentials(account)
    return account
  }

  async #ensureStorage() {
    const filesDir = path.join(this.dir, 'files')
    await fsp.mkdir(filesDir, { recursive: true })

    let items = []
    try {
      const res = await this.#api('/api/admin/storage/list')
      items = Array.isArray(res?.data) ? res.data : res?.data?.content || []
    } catch (err) {
      this.#appendLog(`[ske] 读取存储列表失败：${err.message}\n`)
      return
    }
    if (items.some((item) => (item.mount_path || item.mountPath) === '/')) return

    try {
      await this.#api('/api/admin/storage/create', {
        method: 'POST',
        body: {
          driver: 'Local',
          mount_path: '/',
          order: 0,
          status: 'work',
          addition: JSON.stringify({ root_folder_path: filesDir }),
          cache_expiration: 30,
          webdav_policy: 'native_proxy',
        },
      })
    } catch (err) {
      // The server is up and usable; the user can mount a folder by hand.
      this.#appendLog(`[ske] 创建默认存储失败：${err.message}\n`)
    }
  }

  #runCli(args) {
    return new Promise((resolve, reject) => {
      execFile(
        this.binary,
        args,
        { cwd: this.dir, windowsHide: true, timeout: 60_000 },
        (err, stdout, stderr) => {
          if (err) reject(new Error(`执行 ${this.backend.label} 维护命令失败：${(stderr || err.message).trim()}`))
          else resolve(stdout)
        },
      )
    })
  }

  #credentialsPath() {
    return path.join(this.dir, 'credentials.json')
  }

  async #readCredentials() {
    try {
      const data = JSON.parse(await fsp.readFile(this.#credentialsPath(), 'utf8'))
      const password = unsealSecret(data.secret)
      if (!data.username || !password) return null
      return { username: data.username, password }
    } catch {
      return null
    }
  }

  async #writeCredentials({ username, password }) {
    await fsp.writeFile(
      this.#credentialsPath(),
      JSON.stringify({ username, secret: sealSecret(password) }, null, 2),
      'utf8',
    )
  }

  #openLog() {
    if (this.logStream) return
    try {
      const file = path.join(this.dir, 'server.log')
      // A crashing backend writes a full goroutine dump per exit; without a cap
      // one bad build left a 150 MB log behind. Keep one previous file, nothing
      // more: the log is for support, not for history.
      const stat = fs.statSync(file, { throwIfNoEntry: false })
      if (stat && stat.size > LOG_MAX_BYTES) {
        fs.rmSync(`${file}.old`, { force: true })
        fs.renameSync(file, `${file}.old`)
      }
      this.logStream = fs.createWriteStream(file, { flags: 'a' })
      this.logStream.on('error', () => {
        this.logStream = null
      })
    } catch {
      this.logStream = null
    }
  }

  /** Split streamed chunks into lines: the password line can arrive in two. */
  #appendLog(chunk) {
    this.pending += chunk
    const lines = this.pending.split('\n')
    this.pending = lines.pop() || ''
    for (const raw of lines) {
      const line = raw.replace(ANSI_RE, '').trimEnd()
      if (!line) continue
      this.logs.push(line)
      if (this.logs.length > LOG_LINES) this.logs.shift()
      this.logStream?.write(`${line}\n`)
      const match = line.match(INITIAL_PASSWORD_RE)
      if (match) this.initialPassword = match[1]
    }
  }

  #scanLogsForPassword() {
    for (let i = this.logs.length - 1; i >= 0; i -= 1) {
      const match = this.logs[i].match(INITIAL_PASSWORD_RE)
      if (match) return match[1]
    }
    return ''
  }
}
