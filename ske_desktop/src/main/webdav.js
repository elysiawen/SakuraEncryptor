/**
 * WebDAV gateway: a library, mounted as a drive letter.
 *
 * Windows Explorer, Finder and GNOME/KDE all speak WebDAV natively, so this is
 * the one transport that gives "open it like a folder" on all three desktop
 * platforms without a file-system driver, a signed kernel component or admin
 * rights. The alternative — WinFsp / Cloud Files / ProjFS — would cover one OS
 * each and cost a native process.
 *
 * What Explorer sees is the *plaintext* tree: names come from the library's
 * decrypted listing, and a `.ske` container is served as the bytes inside it.
 * That reuses the same primitives the media endpoint already relies on:
 * `MediaRegistry` opens a container once, then answers byte ranges by decrypting
 * only the 1 MiB blocks a range actually covers.
 *
 * One gateway serves several *shares* (accounts). Each has its own token, its
 * own credential pair and its own target — the whole app, one local folder, or
 * one cloud profile's folder — and the token in the URL decides which one a
 * request belongs to. See `configure()`.
 *
 * Writable: PUT, MKCOL, DELETE and MOVE. A save arrives as plaintext, is
 * encrypted into a container under a fresh name token, and is stored through the
 * same primitives the app's own upload path uses. The format has no append — it
 * is a stream of GCM blocks closed by a MAC — so every save rewrites the whole
 * container; saving a 4 GB video costs a 4 GB re-encryption.
 *
 * File managers litter a writable tree with `Thumbs.db`, `desktop.ini` and
 * `.DS_Store`. Those are accepted and dropped instead of refused: a 403 turns a
 * copy into a modal dialog, and storing them would fill the library with
 * encrypted junk nobody asked for.
 */
import crypto from 'node:crypto'
import fsp from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { mimeFor, parseRange } from './media-router.js'
import { checkName } from './ske-names.js'
import { geometry, looksLikeContainer, parsePrefix, PROBE_SIZE } from './ske-format.js'
import { encryptFile } from './ske-writer.js'
import { makeThumbnail } from './thumbnails.js'

const PREFIX = '/dav'
/** The explicit mount's first path segment, without the leading slash. */
const HTTP_PREFIX = PREFIX.slice(1)

/** Library ids as they appear in the URL. ASCII, so no client needs escaping. */
const LIB_LOCAL = 'local'
const LIB_CLOUD = 'cloud'

/**
 * What one share exposes.
 *
 * `mixed` is the original arrangement: a root with `local/` and `cloud/` under
 * it, the cloud half following whichever profile the app is browsing. `local`
 * and `cloud` are why this class grew accounts — a share that *is* one folder
 * of one library, which is what you want when a drive letter belongs to a
 * specific library rather than to the whole app.
 */
const KIND_MIXED = 'mixed'
const KIND_LOCAL = 'local'
const KIND_CLOUD = 'cloud'

/** Basic-auth username of the first share; later ones become `sakura2`, … */
const DEFAULT_USERNAME = 'sakura'

/** Open containers kept warm: a video seeks far more often than it re-opens. */
const MAX_SESSIONS = 8
/** Response slice size; bounds one allocation while streaming. */
const WRITE_CHUNK = 256 * 1024

const XML_HEAD = '<?xml version="1.0" encoding="utf-8"?>'

/** What this gateway answers, and what OPTIONS advertises. */
const ALLOW = 'OPTIONS, GET, HEAD, PROPFIND, PUT, MKCOL, DELETE, MOVE, PROPPATCH'

/** Bodies are spooled here as plaintext before they are encrypted. */
const SPOOL_DIR = path.join(os.tmpdir(), 'ske-dav')

/**
 * Names every file manager drops into any folder it can write to.
 *
 * Stored as containers they would become encrypted entries in the library: junk
 * the app then has to list, thumbnail and back up. A 403 turns a copy into a
 * modal dialog, so these are accepted and dropped instead.
 */
const JUNK = [
  /^thumbs\.db$/i,
  /^ehthumbs\.db$/i,
  /^desktop\.ini$/i,
  /^\.ds_store$/i,
  /^\._/,
  /^\.apdisk$/i,
  /^\.spotlight-v100$/i,
  /^\.fseventsd$/i,
  /^\.trashes$/i,
]

function isJunk(name) {
  return JUNK.some((pattern) => pattern.test(name))
}

function escapeXml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * A href is a URI: every segment is percent-encoded, directories end in `/`.
 *
 * Links start at the token (`/<token>/local/…`), not at `/dav`: Windows asks
 * for the *share root* — everything after `@port` in the UNC path — so the
 * token has to be that root, or the redirector walks straight past it.
 */
function hrefOf(segments, isDir) {
  const encoded = segments.map((part) => encodeURIComponent(part)).join('/')
  return isDir ? `/${encoded}/` : `/${encoded}`
}

/**
 * `/Movies/HD/` → `/Movies/HD`; `''`, `/` and `.` → `''`.
 *
 * A share's base is the plaintext folder it is rooted at. Normalising it once,
 * on the way in, keeps every comparison downstream a plain string compare.
 */
function normalizeBase(value) {
  const parts = String(value || '')
    .split('/')
    .filter((part) => part && part !== '.')
  return parts.length ? `/${parts.join('/')}` : ''
}

/** A share root plus decoded request segments → its absolute plaintext path. */
function joinPath(base, segments) {
  if (!segments.length) return base || '/'
  return base ? `${base}/${segments.join('/')}` : `/${segments.join('/')}`
}

/** The last component of a base path, for the root entry of a rooted share. */
function baseName(base) {
  return base ? base.slice(base.lastIndexOf('/') + 1) : ''
}

function textEquals(a, b) {
  const left = Buffer.from(String(a ?? ''))
  const right = Buffer.from(String(b ?? ''))
  return left.length === right.length && crypto.timingSafeEqual(left, right)
}

function sendText(res, status, text, headers = {}) {
  const body = Buffer.from(text, 'utf8')
  res.writeHead(status, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Content-Length': body.length,
    ...headers,
  })
  res.end(body)
}

/** An error carrying the status the client should see. */
function statusError(status, message) {
  return Object.assign(new Error(message), { status })
}

/** A status with no body: 204 must not carry one, and 201 needs none. */
function emptyResponse(res, status, headers = {}) {
  res.writeHead(status, { 'Content-Length': 0, ...headers })
  res.end()
}

/** Read and discard a request body, so the client finishes writing cleanly. */
async function drain(req) {
  for await (const chunk of req) void chunk
}

/** Read a small body (PROPPATCH); the tail of an oversized one is drained. */
async function readBody(req, limit = 1024 * 1024) {
  const chunks = []
  let total = 0
  for await (const chunk of req) {
    if (total >= limit) continue
    chunks.push(chunk)
    total += chunk.length
  }
  return Buffer.concat(chunks).toString('utf8')
}

/**
 * Spool a request body to a temp file.
 *
 * The writer needs a source it can seek back into — the header is back-filled
 * once every block is written — and a client that drops the connection halfway
 * must not leave a half-encrypted container in the library. The extension is
 * kept so the thumbnailer recognises an image.
 */
async function spoolBody(req, name) {
  await fsp.mkdir(SPOOL_DIR, { recursive: true })
  const file = path.join(SPOOL_DIR, crypto.randomBytes(8).toString('hex') + path.extname(name))
  const handle = await fsp.open(file, 'w')
  try {
    for await (const chunk of req) await handle.write(chunk)
  } catch (err) {
    await handle.close().catch(() => {})
    await fsp.rm(file, { force: true }).catch(() => {})
    throw err
  }
  await handle.close()
  return file
}

/** Honour backpressure: a seek-heavy client must not queue a whole file. */
function writeChunk(res, chunk) {
  return new Promise((resolve, reject) => {
    if (res.write(chunk)) return resolve()
    const done = () => {
      res.off('drain', done)
      res.off('close', done)
      resolve()
    }
    res.once('drain', done)
    res.once('close', done)
    res.once('error', reject)
  })
}

export class WebdavGateway {
  /**
   * @param {object} deps
   * @param {import('./media-router.js').MediaRegistry} deps.registry
   * @param {import('./local-fs.js').LocalLibrary} deps.localLibrary
   * @param {import('./cloud.js').CloudService} deps.cloud
   * @param {import('./storage.js').Storage} [deps.storage] profiles, for shares that log in on their own
   * @param {() => boolean} [deps.isLocked] app lock state, checked per request
   */
  constructor({ registry, localLibrary, cloud, storage = null, isLocked = () => false }) {
    this.registry = registry
    this.localLibrary = localLibrary
    this.cloud = cloud
    this.storage = storage
    this.isLocked = isLocked

    /**
     * `id -> account`, i.e. one share per account.
     *
     * Each account owns three things a single global could not: its own token
     * (the share name Windows sees), its own credential pair, and the one
     * target it exposes. Filled by `configure()` from the settings file.
     */
    this.accounts = new Map()

    /** `pathKey -> { id, session, touched }`, evicted least-recently-used first. */
    this.sessions = new Map()
    /** The cloud profile currently connected in the UI, if any. */
    this.cloudSessionId = ''
    /** `accountId -> sessionId`, for shares that logged in to a profile themselves. */
    this.cloudShares = new Map()
  }

  /**
   * Replace the share list: once at startup, and whenever the settings page
   * saves.
   *
   * A share's identity is its token, target and base path, so a change to any
   * of them has to drop that share's warm containers: a token that now points
   * at another folder must not keep serving the one it cached a minute ago.
   *
   * Credentials are part of the share, not of the process: a password the user
   * never touches is still generated once and kept, so a mapped drive survives
   * a restart.
   */
  configure(inputs = []) {
    const next = new Map()
    const usernames = new Set()
    let ordinal = 0

    for (const input of inputs) {
      const id = String(input?.id || '')
      const token = String(input?.token || '')
      if (!id || !token) continue

      const previous = this.accounts.get(id)
      // A chosen username wins; otherwise `sakura`, then `sakura2`, `sakura3`:
      // short enough to type into Finder, and stable for a share that keeps its
      // id across edits.
      let username = String(input.username || previous?.username || '').trim()
      if (!username || usernames.has(username)) {
        do {
          ordinal += 1
          username = ordinal === 1 ? DEFAULT_USERNAME : `${DEFAULT_USERNAME}${ordinal}`
        } while (usernames.has(username))
      }
      usernames.add(username)

      const kind = input.kind === KIND_LOCAL || input.kind === KIND_CLOUD ? input.kind : KIND_MIXED
      const account = {
        id,
        name: String(input.name || '').trim() || token.slice(0, 8),
        token,
        username,
        // Empty means "unchanged": an edit that leaves the field alone keeps
        // the running password, and a brand-new share gets one generated here
        // (main then seals it into settings).
        password: String(input.password || previous?.password || crypto.randomBytes(18).toString('base64url')),
        kind,
        // A mixed share spans both libraries, so a base path would be ambiguous.
        profileId: kind === KIND_CLOUD ? String(input.profileId || '') : '',
        base: kind === KIND_MIXED ? '' : normalizeBase(input.base),
      }

      const moved =
        previous &&
        (previous.token !== account.token ||
          previous.kind !== account.kind ||
          previous.profileId !== account.profileId ||
          previous.base !== account.base)
      if (moved) this.#dropSessions({ account: id })

      next.set(id, account)
    }

    for (const id of this.accounts.keys()) {
      if (next.has(id)) continue
      this.#dropSessions({ account: id })
      this.#releaseCloud(id)
    }

    this.accounts = next
  }

  /** Every share, with what the settings card needs to draw it. */
  list() {
    return [...this.accounts.values()].map((account) => ({
      id: account.id,
      name: account.name,
      token: account.token,
      username: account.username,
      password: account.password,
      kind: account.kind,
      profileId: account.profileId,
      base: account.base,
      ...this.#readiness(account),
    }))
  }

  /**
   * One directory of a target, for the settings card's folder picker.
   *
   * It resolves through the same code the gateway uses, so "it lists here"
   * means "it will list over WebDAV": a local library has to be unlocked, and a
   * cloud target logs its profile in on demand. Failures are thrown with a
   * `reason` the caller can translate.
   *
   * @param {{ kind?: string, profileId?: string, path?: string }} target
   * @returns {Promise<{ path: string, parent: string, directories: string[] }>}
   */
  async browse({ kind = KIND_LOCAL, profileId = '', path: relPath = '/' } = {}) {
    const dir = normalizeBase(relPath) || '/'

    let listing
    if (kind === KIND_CLOUD) {
      // One cache slot per profile: browsing a tree must not log in per click,
      // and `dispose()` still knows what to release.
      const session = await this.#cloudSessionFor({
        id: `browse:${profileId}`,
        kind: KIND_CLOUD,
        profileId,
      })
      listing = await this.cloud.list(session.id, dir)
    } else {
      this.#requireLocal()
      listing = await this.localLibrary.list(dir)
    }

    return {
      path: dir,
      // `''` rather than `/` for the root: there is nothing above it.
      parent: dir === '/' ? '' : path.posix.dirname(dir),
      directories: listing.entries
        .filter((entry) => entry.isDir)
        .map((entry) => entry.name)
        .sort((a, b) => a.localeCompare(b)),
    }
  }

  /**
   * Whether one share can answer right now, and — when it cannot — why.
   *
   * The reason is a key, not a sentence: the settings card speaks the user's
   * language, this class does not.
   */
  #readiness(account) {
    if (account.kind === KIND_CLOUD) {
      if (!account.profileId) return { ready: false, reason: 'noProfile' }
      const profile = this.storage?.listProfiles()?.profiles.find((item) => item.id === account.profileId)
      if (!profile) return { ready: false, reason: 'profileMissing' }
      if (!profile.hasSkePassword) return { ready: false, reason: 'profileLocked' }
      return { ready: true, reason: '' }
    }

    const local = !!(this.localLibrary?.unlocked && this.localLibrary?.root)
    if (account.kind === KIND_LOCAL) {
      return local ? { ready: true, reason: '' } : { ready: false, reason: 'localLocked' }
    }
    // A mixed share is ready when either half is; the root listing shows the
    // halves that are, and hides the rest.
    const cloud = !!this.#uiCloudSession()
    if (local || cloud) return { ready: true, reason: '' }
    return { ready: false, reason: 'localLocked' }
  }

  /** Follow the profile the UI is browsing, for shares that expose everything. */
  setCloudSession(sessionId) {
    if (sessionId === this.cloudSessionId) return
    this.cloudSessionId = sessionId || ''
    this.#dropSessions({ lib: LIB_CLOUD })
  }

  clearCloudSession() {
    this.setCloudSession('')
  }

  /** Whether a library can answer right now; the UI and the root listing use it. */
  status() {
    return {
      local: !!(this.localLibrary?.unlocked && this.localLibrary?.root),
      cloud: !!this.#uiCloudSession(),
    }
  }

  /** Close every warm container — on lock, on disconnect, on quit. */
  async dispose() {
    const ids = [...this.sessions.values()].map((entry) => entry.id)
    this.sessions.clear()
    for (const id of [...this.cloudShares.keys()]) this.#releaseCloud(id)
    await Promise.all(ids.map((id) => this.registry.close(id)))
  }

  // --- request handling ----------------------------------------------------

  /**
   * Handle a `/dav...` request.
   *
   * @returns {Promise<boolean>} true when the request belonged to this gateway
   */
  async handle(req, res, url) {
    const raw = this.#segments(url.pathname)
    const explicit = url.pathname === PREFIX || url.pathname.startsWith(`${PREFIX}/`)

    if (!raw) {
      if (!explicit) return false
      sendText(res, 400, 'Bad path')
      return true
    }

    // Two mount shapes, because two kinds of client exist.
    //
    // `/dav/…` is the explicit form: Basic clients point at it directly.
    // `/<token>/…` is what Windows gets, and it has to be the *share root*:
    // the redirector keys the connection on whatever follows `@port` in the UNC
    // path and drops anything deeper, so a token buried inside `/dav` is gone by
    // the time Explorer lists the folder.
    //
    // With accounts, the token also says *which* share: one token per account,
    // and each account carries its own target. The explicit form cannot do that
    // — its first segment is a library name, not a share — so there the Basic
    // username picks the account instead.
    const scoped = raw[0] === HTTP_PREFIX ? raw.slice(1) : raw
    let account = scoped.length > 0 ? this.#accountByToken(scoped[0]) : null
    let segments = account ? scoped.slice(1) : scoped

    // Discovery first: Windows asks OPTIONS before it has credentials to send,
    // and refusing it there turns a mapping attempt into a generic "cannot
    // connect" dialog with no clue in it.
    if (req.method === 'OPTIONS' && (account || explicit)) return this.#options(res)

    if (!account) {
      if (!explicit) return false
      account = this.#accountByBasic(req)
      if (!account) {
        sendText(res, 401, 'Authentication required', {
          'WWW-Authenticate': 'Basic realm="Sakura Encryptor", charset="UTF-8"',
        })
        return true
      }
      // `/dav/<username>/…` is tolerated: it is what a hand-written URL looks
      // like, and it keeps one account's links from colliding with another's.
      if (segments.length && textEquals(segments[0], account.username)) segments = segments.slice(1)
    }

    if (this.isLocked()) {
      // 423 rather than 401: the credentials are fine, the vault is shut.
      sendText(res, 423, 'The app is locked')
      return true
    }

    try {
      if (req.method === 'PROPFIND') await this.#propfind(req, res, account, segments)
      else if (req.method === 'GET' || req.method === 'HEAD') await this.#get(req, res, account, segments)
      else if (req.method === 'PUT') await this.#put(req, res, account, segments)
      else if (req.method === 'MKCOL') await this.#mkcol(res, account, segments)
      else if (req.method === 'DELETE') await this.#delete(res, account, segments)
      else if (req.method === 'MOVE') await this.#move(req, res, account, segments)
      else if (req.method === 'PROPPATCH') await this.#proppatch(req, res, account, segments)
      else sendText(res, 405, 'Method not allowed', { Allow: ALLOW })
    } catch (err) {
      // A stale Explorer cache asking for a file that moved, or a storage driver
      // answering "object not found", is normal traffic — not a server fault.
      const missing = err?.code === 'ENOENT' || /not found/i.test(String(err?.message || ''))
      const status = err?.status || (missing ? 404 : 500)
      if (status >= 500) console.error(`[dav] ${req.method} ${url.pathname}`, err.message)
      if (!res.headersSent) sendText(res, status, err.message)
      else res.destroy(err)
    }
    return true
  }

  #options(res) {
    res.writeHead(200, {
      DAV: '1',
      // MS-Author-Via is what makes Explorer treat this as a WebDAV folder
      // rather than "a website that answered OPTIONS".
      'MS-Author-Via': 'DAV',
      Allow: ALLOW,
      'Content-Length': 0,
    })
    res.end()
    return true
  }

  /** The share whose token this path segment is, if any. */
  #accountByToken(value) {
    for (const account of this.accounts.values()) {
      if (textEquals(account.token, value)) return account
    }
    return null
  }

  /**
   * Basic auth → the share whose credentials these are.
   *
   * Usernames are unique per share, so this is one textual comparison each, and
   * the password check stays constant-time.
   */
  #accountByBasic(req) {
    const header = req.headers.authorization || ''
    const match = /^Basic\s+(\S+)$/i.exec(header)
    if (!match) return null
    const decoded = Buffer.from(match[1], 'base64').toString('utf8')
    const split = decoded.indexOf(':')
    if (split < 0) return null
    const username = decoded.slice(0, split)
    const password = decoded.slice(split + 1)
    for (const account of this.accounts.values()) {
      if (textEquals(account.username, username) && textEquals(account.password, password)) return account
    }
    return null
  }

  /** `/a/b` → `['a', 'b']`; null when the path tries to walk upwards. */
  #segments(pathname) {
    const parts = pathname.split('/').filter(Boolean)
    const decoded = []
    for (const part of parts) {
      let value
      try {
        value = decodeURIComponent(part)
      } catch {
        return null
      }
      if (value === '.' || value === '..' || value.includes('\0')) return null
      decoded.push(value)
    }
    return decoded
  }

  #uiCloudSession() {
    if (!this.cloudSessionId) return null
    return this.cloud.sessions.get(this.cloudSessionId) || null
  }

  /**
   * Request segments → the library they land in, and the plaintext path inside
   * it.
   *
   * A `mixed` share keeps the old shape, where the first segment names the
   * library. A rooted share (`local` / `cloud`) maps its own root onto `base`,
   * so the drive letter shows the folder the user picked, not a wrapper around
   * it — and a client never sees the base path in a URL.
   *
   * @returns {{ lib: string, relPath: string, root: boolean } | null}
   */
  #route(account, segments) {
    if (account.kind === KIND_MIXED) {
      if (!segments.length) return { lib: '', relPath: '/', root: true }
      const [lib, ...rest] = segments
      if (lib !== LIB_LOCAL && lib !== LIB_CLOUD) return null
      return { lib, relPath: `/${rest.join('/')}`, root: rest.length === 0 }
    }
    return { lib: account.kind, relPath: joinPath(account.base, segments), root: segments.length === 0 }
  }

  /**
   * `#route`, plus the `/cloud/…` alias a mixed share uses.
   *
   * A mapped drive holds URLs, and switching one share from mixed to cloud must
   * not silently change them: keeping `/token/cloud/本子` valid is the difference
   * between "one account fewer" and "remap the drive". The alias is dropped only
   * when the cloud root has no folder actually called `cloud` — a real folder
   * always wins, and mixed never even asks (there the segment is the library).
   */
  async #routeOf(account, segments) {
    const plain = this.#route(account, segments)
    if (account.kind !== KIND_CLOUD) return plain
    if (segments[0] !== LIB_CLOUD) return plain

    // A share with no usable login keeps the plain answer: the request fails
    // later with the reason, rather than with a path that was quietly rewritten.
    const session = await this.#cloudSessionFor(account).catch(() => null)
    if (!session) return plain
    return (await this.#stripCloudAlias(session))
      ? this.#route(account, segments.slice(1))
      : plain
  }

  /**
   * Whether this login should read `/cloud` as the alias rather than a folder.
   *
   * Decided once per session: one listing at the root, then every request in
   * the folder is free.
   */
  async #stripCloudAlias(session) {
    if (session.cloudAlias === undefined) {
      const listing = await this.cloud.list(session.id, '/').catch(() => null)
      session.cloudAlias = !listing || !listing.entries.some((entry) => entry.name === LIB_CLOUD)
    }
    return session.cloudAlias
  }

  /**
   * The cloud session one share reads from.
   *
   * A `cloud` share names a profile and logs in for itself: it must not depend
   * on what the UI happens to be browsing, or a second share would follow the
   * first one's folder. A `mixed` share *is* the app's own view, so it follows
   * the connected profile exactly as before.
   */
  async #cloudSessionFor(account) {
    if (account.kind !== KIND_CLOUD) {
      const session = this.#uiCloudSession()
      if (!session) {
        throw Object.assign(new Error('No cloud profile is connected'), { status: 423, reason: 'noSession' })
      }
      return session
    }

    if (!account.profileId) {
      throw Object.assign(new Error('This share has no cloud profile'), { status: 423, reason: 'noProfile' })
    }

    const own = this.cloudShares.get(account.id)
    const reusable = own ? this.cloud.sessions.get(own) : null
    if (reusable?.profileId === account.profileId) return reusable

    // The UI may already have this profile open: reuse that login rather than
    // authenticating again — and rather than failing on a 2FA profile the user
    // opened interactively a minute ago.
    for (const session of this.cloud.sessions.values()) {
      if (session.profileId !== account.profileId) continue
      this.cloudShares.set(account.id, session.id)
      return session
    }

    const sessionId = await this.#login(account)
    this.cloudShares.set(account.id, sessionId)
    return this.cloud.get(sessionId)
  }

  /**
   * Log a share in from the vault, unattended.
   *
   * A profile that wants a one-time code cannot be opened this way: there is no
   * one to ask over WebDAV, so the answer is 423 and the settings card says so.
   * The user connects that profile once in the app and the session is reused.
   */
  async #login(account) {
    const profile = this.storage?.listProfiles()?.profiles.find((item) => item.id === account.profileId)
    if (!profile) {
      throw Object.assign(new Error('The cloud profile of this share no longer exists'), {
        status: 423,
        reason: 'profileMissing',
      })
    }
    const secrets = this.storage.getSecrets(profile.id)
    if (!secrets?.skePassword) {
      throw Object.assign(new Error('The cloud profile of this share has no media password saved'), {
        status: 423,
        reason: 'profileLocked',
      })
    }

    try {
      const result = await this.cloud.login({
        profile,
        password: secrets.password,
        skePassword: secrets.skePassword,
      })
      return result.sessionId
    } catch (err) {
      const reason = err?.needsOtp
        ? 'This share needs a one-time code: connect the profile in the app first'
        : err?.message
      throw Object.assign(new Error(reason || 'Cannot open the cloud profile of this share'), {
        status: 423,
        reason: err?.needsOtp ? 'needsOtp' : 'loginFailed',
      })
    }
  }

  /** Drop a share's own login; never the one the UI is using. */
  #releaseCloud(accountId) {
    const sessionId = this.cloudShares.get(accountId)
    if (!sessionId) return
    this.cloudShares.delete(accountId)
    if (sessionId !== this.cloudSessionId) this.cloud.logout(sessionId)
  }

  // --- PROPFIND ------------------------------------------------------------

  async #propfind(req, res, account, segments) {
    const depth = String(req.headers.depth || '1').trim()
    // `infinity` would walk the whole tree; every client here copes with 1, and
    // capping it is what keeps a stray request from enumerating a 40 GB library.
    const shallow = depth === '0'

    const entries = []

    if (account.kind === KIND_MIXED && segments.length === 0) {
      entries.push({ segments: [], isDir: true, name: account.name, size: 0 })
      if (!shallow) {
        const ready = this.status()
        if (ready.local) entries.push({ segments: [LIB_LOCAL], isDir: true, name: LIB_LOCAL, size: 0 })
        if (ready.cloud) entries.push({ segments: [LIB_CLOUD], isDir: true, name: LIB_CLOUD, size: 0 })
      }
    } else {
      // A rooted share has no wrapper folder: its own root *is* the target, so
      // an empty request path is described from the base like any other.
      const target = await this.#describe(account, segments)
      entries.push(target)
      if (!shallow && target.isDir) {
        for (const child of await this.#children(account, segments)) entries.push(child)
      }
    }

    const body = Buffer.from(
      `${XML_HEAD}<D:multistatus xmlns:D="DAV:">${entries.map((entry) => this.#responseXml(account, entry)).join('')}</D:multistatus>`,
      'utf8',
    )
    res.writeHead(207, {
      // `text/xml` with an explicit charset: Windows rejects the 207 otherwise.
      'Content-Type': 'text/xml; charset="utf-8"',
      'Content-Length': body.length,
    })
    res.end(req.method === 'HEAD' ? undefined : body)
  }

  /**
   * Every link we emit carries the share's token, so a client that arrived
   * through the secret path never has to authenticate again when it walks into
   * a subfolder — and links stay inside the share they came from.
   */
  #href(account, segments, isDir) {
    return hrefOf([account.token, ...segments], isDir)
  }

  #responseXml(account, entry) {
    const href = this.#href(account, entry.segments, entry.isDir)
    const props = [
      `<D:displayname>${escapeXml(entry.name)}</D:displayname>`,
      entry.isDir
        ? '<D:resourcetype><D:collection/></D:resourcetype>'
        : '<D:resourcetype/>',
    ]
    if (!entry.isDir) {
      props.push(`<D:getcontentlength>${entry.size}</D:getcontentlength>`)
      props.push(`<D:getcontenttype>${escapeXml(entry.mime || 'application/octet-stream')}</D:getcontenttype>`)
    }
    // A missing timestamp is rendered by Windows as the FILETIME epoch
    // (1601-01-01), which reads as corruption. Claiming "now" for entries whose
    // mtime we do not know is what every other WebDAV server does.
    const modified = entry.modified ? new Date(entry.modified) : new Date()
    if (!Number.isNaN(modified.getTime())) {
      props.push(`<D:getlastmodified>${modified.toUTCString()}</D:getlastmodified>`)
    }
    return `<D:response><D:href>${escapeXml(href)}</D:href><D:propstat><D:prop>${props.join(
      '',
    )}</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>`
  }

  /**
   * One entry, for the requested path itself. Throws status-bearing errors.
   *
   * The entry's `segments` are the *request* segments, not the routed path: a
   * href has to be built from what the client asked for, so a share rooted at
   * `/Movies` keeps `/Movies` out of its URLs.
   */
  async #describe(account, segments) {
    const route = await this.#routeOf(account, segments)
    if (!route) throw Object.assign(new Error('Unknown library'), { status: 404 })

    // The root of a rooted share is named after the folder it points at.
    const name = segments.length
      ? segments[segments.length - 1]
      : baseName(account.base) || account.name

    if (route.lib === LIB_LOCAL) return this.#describeLocal(route.relPath, segments, name)
    return this.#describeCloud(account, route.relPath, segments, name)
  }

  /**
   * Describe one cloud entry by asking its *parent*.
   *
   * The obvious way — probe the path, treat a failure as "it is a folder" — is
   * what produced `[dav] failed get objs: failed get dir: object not found` in
   * the console: for a plain file (or a cold path index) the file probe fails,
   * so it fell through to listing a *file*, which the storage answers with a
   * driver-level error and we reported as a 500. Reading the parent listing
   * once decides "folder or file" without ever issuing a request known to fail.
   */
  async #describeCloud(account, relPath, segments, name) {
    const session = await this.#cloudSessionFor(account)
    const leaf = path.posix.basename(relPath)

    // A share rooted at a folder describes that folder as a collection by
    // construction, exactly like the library root it may be.
    if (!leaf) return { segments, isDir: true, name, size: 0 }

    const dir = path.posix.dirname(relPath)
    try {
      await this.cloud.storedDirOf(session.id, dir)
    } catch {
      // The parent folder is not in the storage at all: 404 is the honest answer,
      // and it ends the retry loop Explorer otherwise holds open on a stale path.
      throw statusError(404, 'Not found')
    }

    // Forced for the same reason as `#existsCloud`: a client asks this question
    // right before and right after a write, and a cached answer to either one is
    // wrong in a way that shows up as "there is already a file with that name"
    // for an empty folder, or as a copy that reports success without a file.
    const listing = await this.#freshList(session, dir)
    const entry = listing.entries.find((item) => item.name === leaf)
    if (!entry) throw statusError(404, 'Not found')

    return {
      segments,
      isDir: entry.isDir,
      name: entry.name,
      // `list` already reports a plaintext length for files this session wrote;
      // the storage's own number would be the container, which is larger by the
      // per-block overhead and reads as a corrupt copy to a client that checks.
      size: entry.isDir ? 0 : entry.size,
      modified: entry.modified || '',
      mime: entry.isDir ? '' : mimeFor(entry.name),
    }
  }

  /**
   * Resolve a display path to a real one, repairing a cold index on the way.
   *
   * `resolveInside` can encrypt every component, but without the index it
   * cannot tell a file from a folder: folders are bare tokens, files end in
   * `.ske`. The app always resolves paths it just listed, so this never came
   * up; a WebDAV client happily GETs a file it never saw listed (a bookmark, a
   * stale Explorer cache), and that miss has to be repaired rather than 404'd.
   */
  async #localEntry(relPath) {
    const attempt = async () => {
      const absolute = this.localLibrary.resolveInside(relPath)
      return { absolute, stat: await fsp.stat(absolute) }
    }

    try {
      return await attempt()
    } catch (err) {
      if (err?.code !== 'ENOENT') throw err
      await this.localLibrary.list(path.posix.dirname(relPath)).catch(() => {})
      return attempt()
    }
  }

  /**
   * Describe a single local path without listing its parent.
   *
   * A directory answers with its own entry; a file needs `size`, which is the
   * plaintext length — cheap to derive from the 99-byte probe, since that
   * carries the thumbnail length and therefore the body offset.
   */
  async #describeLocal(relPath, segments, name) {
    // Before touching the filesystem: a locked library must answer 423, not the
    // 500 that a missing root would otherwise produce.
    this.#requireLocal()
    const { absolute, stat } = await this.#localEntry(relPath)
    if (stat.isDirectory()) {
      return { segments, isDir: true, name, size: 0 }
    }
    return {
      segments,
      isDir: false,
      name,
      size: await this.#plainSize(absolute, stat.size),
      modified: stat.mtime,
      mime: mimeFor(name),
    }
  }

  /** Plaintext length of a container, read from the probe alone (no key). */
  async #plainSize(absolute, cipherSize) {
    if (!cipherSize) return 0
    let handle
    try {
      handle = await fsp.open(absolute, 'r')
      const buffer = Buffer.alloc(PROBE_SIZE)
      const { bytesRead } = await handle.read(buffer, 0, PROBE_SIZE, 0)
      const probe = buffer.subarray(0, bytesRead)
      if (!looksLikeContainer(probe)) return cipherSize
      return geometry(cipherSize, parsePrefix(probe).bodyOffset).plainSize
    } catch {
      // A file mid-write, or a foreign format: the ciphertext size still lets
      // Explorer draw the row, and GET reports the exact length.
      return cipherSize
    } finally {
      await handle?.close().catch(() => {})
    }
  }

  async #children(account, segments) {
    const route = await this.#routeOf(account, segments)
    if (!route) return []
    if (route.lib === LIB_LOCAL) return this.#localChildren(route.relPath, segments)
    return this.#cloudChildren(account, route.relPath, segments)
  }

  #requireLocal() {
    // `reason` travels to the settings card, which turns it into its own words.
    if (!this.localLibrary?.unlocked) {
      throw Object.assign(new Error('Local library is locked'), { status: 423, reason: 'localLocked' })
    }
    if (!this.localLibrary.root) {
      throw Object.assign(new Error('No local folder chosen'), { status: 404, reason: 'localEmpty' })
    }
  }

  async #localChildren(relPath, segments) {
    this.#requireLocal()
    const listing = await this.localLibrary.list(relPath)
    return Promise.all(
      listing.entries.map(async (entry) => {
        const childSegments = [...segments, entry.name]
        if (entry.isDir) {
          return { segments: childSegments, isDir: true, name: entry.name, size: 0 }
        }
        const absolute = entry.path ? this.localLibrary.resolveInside(entry.path) : ''
        return {
          segments: childSegments,
          isDir: false,
          name: entry.name,
          size: absolute ? await this.#plainSize(absolute, entry.size) : entry.size,
          mime: mimeFor(entry.name),
        }
      }),
    )
  }

  async #cloudChildren(account, relPath, segments) {
    const session = await this.#cloudSessionFor(account)
    const listing = await this.cloud.list(session.id, relPath)
    return listing.entries.map((entry) => ({
      segments: [...segments, entry.name],
      isDir: entry.isDir,
      name: entry.name,
      // The listing reports the stored size, which is a few bytes per MiB larger
      // than the plaintext. Probing every entry would cost one ranged request
      // each, and Explorer only uses this for the size column.
      size: entry.isDir ? 0 : entry.size,
      modified: entry.modified || '',
      mime: entry.isDir ? '' : mimeFor(entry.name),
    }))
  }

  // --- GET / HEAD ----------------------------------------------------------

  async #get(req, res, account, segments) {
    const route = await this.#routeOf(account, segments)
    if (!route) {
      sendText(res, 404, 'Unknown library')
      return
    }
    if (route.root) {
      sendText(res, 405, 'A collection has no body')
      return
    }

    const { key, target, password, name } = await this.#resolveFile(account, route, segments)
    const session = await this.#sessionFor(key, { target, password, name })
    const size = session.plainSize

    const range = parseRange(req.headers.range, size)
    if (range?.unsatisfiable) {
      res.writeHead(416, { 'Content-Range': `bytes */${size}` })
      res.end()
      return
    }

    // A full request is 200, a partial one 206 — and either way the length is
    // what this response actually carries, not the file's total.
    const start = range ? range.start : 0
    const end = size === 0 ? -1 : range ? range.end : size - 1
    const length = size === 0 ? 0 : end - start + 1

    const headers = {
      'Content-Type': session.mime || mimeFor(name),
      'Accept-Ranges': 'bytes',
      'Content-Length': length,
      ...(range ? { 'Content-Range': `bytes ${start}-${end}/${size}` } : {}),
    }

    if (req.method === 'HEAD' || length === 0) {
      res.writeHead(range ? 206 : 200, headers)
      res.end()
      return
    }

    res.writeHead(range ? 206 : 200, headers)
    for await (const chunk of session.readRange(start, end)) {
      if (res.destroyed) return
      await writeChunk(res, chunk)
    }
    res.end()
  }

  /** Everything GET needs to open a file, without opening it yet. */
  async #resolveFile(account, route, segments) {
    const name = segments[segments.length - 1]

    if (route.lib === LIB_LOCAL) {
      this.#requireLocal()
      const { absolute, stat } = await this.#localEntry(route.relPath)
      if (stat.isDirectory()) throw Object.assign(new Error('Is a collection'), { status: 405 })
      return {
        // The account is part of the key: two shares can point at the same
        // folder with different credentials. The size is too: a file replaced
        // in place must not be served from the previous container's cache.
        key: `${account.id}|${LIB_LOCAL}|${route.relPath}:${stat.size}:${stat.mtimeMs}`,
        target: absolute,
        password: this.localLibrary.password,
        name,
        size: stat.size,
      }
    }

    const session = await this.#cloudSessionFor(account)

    let resolved
    try {
      resolved = await this.cloud.resolve(session.id, route.relPath)
    } catch (err) {
      // `resolve` guesses `<name>.ske` for anything the path index has not
      // seen, so a plain file misses once. Reading the parent repairs the
      // guess; if it still fails the original error is the honest one.
      // Force the listing: the miss is often a file this app just wrote, and the
      // storage's cached listing is older than that write.
      const dir = path.posix.dirname(route.relPath)
      await this.cloud.storedDirOf(session.id, dir).catch(() => {})
      await this.cloud.list(session.id, dir, { refresh: true }).catch(() => {})
      resolved = await this.cloud.resolve(session.id, route.relPath).catch(() => {
        throw err
      })
    }

    return {
      key: `${account.id}|${LIB_CLOUD}|${route.relPath}:${resolved.size}`,
      target: resolved.rawUrl,
      password: session.skePassword,
      name,
      size: resolved.size,
    }
  }

  /**
   * Open a container once and keep it warm.
   *
   * Without this, every range request would re-derive the key: PBKDF2 at 100k
   * iterations costs tens of milliseconds, which a seeking video player pays on
   * every single seek.
   */
  async #sessionFor(key, options) {
    const cached = this.sessions.get(key)
    if (cached) {
      cached.touched = Date.now()
      // Refresh LRU order: Map keeps insertion order, so re-inserting moves it.
      this.sessions.delete(key)
      this.sessions.set(key, cached)
      return cached.session
    }

    const info = await this.registry.open(options)
    const session = this.registry.get(info.id)
    this.sessions.set(key, { id: info.id, session, touched: Date.now() })
    await this.#trimSessions()
    return session
  }

  async #trimSessions() {
    while (this.sessions.size > MAX_SESSIONS) {
      const [key, entry] = this.sessions.entries().next().value
      this.sessions.delete(key)
      await this.registry.close(entry.id)
    }
  }

  // --- writes --------------------------------------------------------------

  /**
   * The plaintext path a write lands on, plus its folder and leaf name.
   *
   * A share root is not a writable resource: PUT has no name to store it under,
   * and deleting or moving the root would unmount the share from inside itself.
   */
  async #writeTarget(account, segments) {
    const route = await this.#routeOf(account, segments)
    if (!route || !route.lib) throw statusError(409, 'The share root is not a file')
    if (route.root) throw statusError(405, 'The share root cannot be changed')

    const dir = path.posix.dirname(route.relPath)
    if (!path.posix.basename(route.relPath)) throw statusError(409, 'The share root is not a file')

    let name
    try {
      // The same rule the app applies to a typed name: one component, no
      // separators, nothing that would silently land in another folder.
      name = checkName(path.posix.basename(route.relPath))
    } catch (err) {
      throw statusError(400, err.message)
    }
    return { lib: route.lib, relPath: route.relPath, dir, name }
  }

  /** Whether a local path exists, repairing a cold index the way reads do. */
  async #existsLocal(relPath) {
    if (await this.localLibrary.exists(relPath)) return true
    await this.localLibrary.list(path.posix.dirname(relPath)).catch(() => {})
    return this.localLibrary.exists(relPath)
  }

  /**
   * A listing forced past the storage's own directory cache.
   *
   * A cached listing keeps answering with rows that were deleted outside this
   * app, and a stale "yes" is what turns an empty folder into a "there is
   * already a file with that name" dialog — or a failed delete. So the answer
   * has to come from the storage.
   *
   * Forced listings are real round trips, and Explorer asks the same question
   * once per file while copying a batch. One refresh per folder per second
   * answers all of them correctly: the cache only lags by a beat, not by a
   * hundred requests.
   */
  async #freshList(session, dir) {
    const now = Date.now()
    session.freshAt ??= new Map()
    const refresh = now - (session.freshAt.get(dir) || 0) > 1000
    if (refresh) session.freshAt.set(dir, now)
    return this.cloud.list(session.id, dir, { refresh })
  }

  /** Same question for a cloud folder: the listing is the only answer it has. */
  async #existsCloud(session, relPath) {
    const dir = path.posix.dirname(relPath)
    // The listing needs the folder resolved first, and a folder created outside
    // this app is only resolvable by walking the tree.
    await this.cloud.storedDirOf(session.id, dir)
    const listing = await this.#freshList(session, dir)
    return listing.entries.some((entry) => entry.name === path.posix.basename(relPath))
  }

  /**
   * PUT: the body is plaintext and becomes a container.
   *
   * Answered 201 when the name was free and 204 when something was replaced,
   * which is what a file manager expects when it copies over an existing file.
   */
  async #put(req, res, account, segments) {
    let target
    try {
      target = await this.#writeTarget(account, segments)
    } catch (err) {
      // The body is still arriving. Answering before draining it resets the
      // connection, and a reset copy is indistinguishable from a broken share in
      // Explorer — the error text never gets there.
      await drain(req).catch(() => {})
      throw err
    }

    if (isJunk(target.name)) {
      // The body is consumed before answering: a client still writing when the
      // response arrives reports a broken pipe instead of the 201.
      await drain(req)
      emptyResponse(res, 201)
      return
    }

    const spool = await spoolBody(req, target.name)
    try {
      const existed = await this.#store(account, target, spool)
      emptyResponse(res, existed ? 204 : 201)
    } finally {
      await fsp.rm(spool, { force: true }).catch(() => {})
    }
  }

  /**
   * Encrypt a spooled body into the library the write targets.
   *
   * @returns {Promise<boolean>} whether something already answered to that name
   */
  async #store(account, target, plain) {
    // Best effort: a missing ffmpeg must not turn a save into an error, and the
    // tile it produces is the same one a queued upload would get.
    const thumbnail = await makeThumbnail(plain).catch(() => null)
    // The length the client sent, which is what it will compare against a listing
    // right after the copy. The container on disk is a little larger by design.
    const plainSize = (await fsp.stat(plain).catch(() => null))?.size || 0

    if (target.lib === LIB_LOCAL) {
      this.#requireLocal()
      // A file cannot land in a folder that is not there: letting the writer
      // create the tree would answer a typo with a silent orphan folder.
      if (!(await this.#existsLocal(target.dir))) throw statusError(409, 'The folder does not exist')

      const write = await this.localLibrary.writeTarget(target.relPath)
      // A plain file, or a container under an older token, answers to the same
      // name: left in place it would show up as a second row for one file.
      if (write.replace) await fsp.rm(write.replace, { recursive: true, force: true })
      await encryptFile({
        src: plain,
        dst: write.absolute,
        password: this.localLibrary.password,
        thumbnail,
      })
      // Whatever was warm for this share described the old contents.
      this.#dropSessions({ account: account.id, lib: LIB_LOCAL })
      return write.existed
    }

    const session = await this.#cloudSessionFor(account)
    if (!session.skePassword) {
      throw statusError(423, 'This share has no media password saved')
    }
    // Same rule as the local half: a file cannot land in a folder that is not
    // there, and the storage's own "no such file or directory" would arrive
    // after the whole upload had already been encrypted.
    try {
      await this.cloud.storedDirOf(session.id, target.dir)
    } catch {
      throw statusError(409, 'The folder does not exist')
    }

    const container = path.join(SPOOL_DIR, `${crypto.randomBytes(8).toString('hex')}.ske`)
    try {
      await encryptFile({
        src: plain,
        dst: container,
        password: session.skePassword,
        thumbnail,
      })
      const stored = await this.cloud.storeContainer(session.id, target.relPath, container, {
        plainSize,
      })
      this.#dropSessions({ account: account.id, lib: LIB_CLOUD })
      return stored.existed
    } finally {
      await fsp.rm(container, { force: true }).catch(() => {})
    }
  }

  /** MKCOL: one new folder, name encrypted like every other entry. */
  async #mkcol(res, account, segments) {
    const target = await this.#writeTarget(account, segments)

    if (isJunk(target.name)) {
      emptyResponse(res, 201)
      return
    }

    if (target.lib === LIB_LOCAL) {
      this.#requireLocal()
      if (!(await this.#existsLocal(target.dir))) throw statusError(409, 'The folder does not exist')
      if (await this.#existsLocal(target.relPath)) {
        // RFC 4918 §9.3.1: a collection that is already there is not created.
        throw statusError(405, 'That folder already exists')
      }
      await this.localLibrary.makeDir(target.dir, target.name)
    } else {
      const session = await this.#cloudSessionFor(account)
      if (await this.#existsCloud(session, target.relPath)) {
        throw statusError(405, 'That folder already exists')
      }
      await this.cloud.makeDir(session.id, target.dir, target.name)
    }

    emptyResponse(res, 201)
  }

  /** DELETE: gone means gone — the app has no trash to fall back on either. */
  async #delete(res, account, segments) {
    const target = await this.#writeTarget(account, segments)

    if (target.lib === LIB_LOCAL) {
      this.#requireLocal()
      if (!(await this.#existsLocal(target.relPath))) throw statusError(404, 'Not found')
      await this.localLibrary.remove(target.relPath)
    } else {
      const session = await this.#cloudSessionFor(account)
      // `remove` works from the stored path in the index, and a cold session has
      // none: resolve the folder (walking the tree if need be), then list it.
      await this.cloud.storedDirOf(session.id, target.dir)
      await this.cloud.list(session.id, target.dir)
      await this.cloud.remove(session.id, target.relPath)
    }

    this.#dropSessions({ account: account.id, lib: target.lib })
    emptyResponse(res, 204)
  }

  /**
   * MOVE: a rename, or a move into another folder.
   *
   * The destination comes from the `Destination` header, always as an absolute
   * URL on this same share. A local folder can move anywhere inside the library;
   * a cloud one can only be renamed in place, because the storage API has no
   * cross-folder move — the alternative would be downloading and re-uploading a
   * container behind the user's back, which is a different feature.
   */
  async #move(req, res, account, segments) {
    const source = await this.#writeTarget(account, segments)
    const destination = await this.#destinationOf(req, account)

    if (destination.lib !== source.lib) {
      throw statusError(502, 'Moving between libraries is not supported')
    }
    if (destination.relPath === source.relPath) {
      emptyResponse(res, 204)
      return
    }

    // `Overwrite: F` is the client saying "do not replace"; anything else may.
    const overwrite = String(req.headers.overwrite || 'T').trim().toUpperCase() !== 'F'

    if (source.lib === LIB_LOCAL) {
      this.#requireLocal()
      if (!(await this.#existsLocal(source.relPath))) throw statusError(404, 'Not found')

      const existed = await this.#existsLocal(destination.relPath)
      if (existed && !overwrite) throw statusError(412, 'The destination already exists')
      if (existed) await this.localLibrary.remove(destination.relPath)

      await this.localLibrary.move(source.relPath, destination.dir, destination.name)
      this.#dropSessions({ account: account.id, lib: LIB_LOCAL })
      emptyResponse(res, existed ? 204 : 201)
      return
    }

    if (destination.dir !== source.dir) {
      throw statusError(502, 'Cloud shares can only be renamed in place')
    }

    const session = await this.#cloudSessionFor(account)
    if (!(await this.#existsCloud(session, source.relPath))) throw statusError(404, 'Not found')

    const existed = await this.#existsCloud(session, destination.relPath)
    if (existed && !overwrite) throw statusError(412, 'The destination already exists')
    if (existed) await this.cloud.remove(session.id, destination.relPath)

    try {
      await this.cloud.rename(session.id, source.relPath, destination.name)
    } catch (err) {
      // A cold index cannot rename an entry it never listed; one listing fixes it.
      if (err?.status === 423) throw err
      await this.cloud.list(session.id, source.dir).catch(() => {})
      await this.cloud.rename(session.id, source.relPath, destination.name)
    }

    this.#dropSessions({ account: account.id, lib: LIB_CLOUD })
    emptyResponse(res, existed ? 204 : 201)
  }

  /**
   * `Destination` header → the same shape `#writeTarget` returns.
   *
   * Two forms exist and both may appear here: `/dav/<user>/…` (Basic clients)
   * and `/<token>/…` (what Windows was mapped with). The account is already
   * known from the request, so the header only has to agree with it; a header
   * naming another share is a copy-and-delete, not a rename.
   */
  async #destinationOf(req, account) {
    const raw = String(req.headers.destination || '')
    if (!raw) throw statusError(400, 'MOVE needs a Destination header')

    let url
    try {
      url = new URL(raw, 'http://localhost')
    } catch {
      throw statusError(400, 'Bad Destination header')
    }

    let parts = this.#segments(url.pathname)
    if (!parts) throw statusError(400, 'Bad Destination path')

    if (parts[0] === HTTP_PREFIX) {
      parts = parts.slice(1)
      if (parts.length && textEquals(parts[0], account.username)) parts = parts.slice(1)
    } else if (parts.length && textEquals(parts[0], account.token)) {
      parts = parts.slice(1)
    } else {
      throw statusError(502, 'That destination is not in this share')
    }

    return await this.#writeTarget(account, parts)
  }

  /**
   * PROPPATCH: accept and forget.
   *
   * Explorer sets the file time this way right after a PUT, and Office writes a
   * handful of dead properties. Nothing here stores metadata — the container
   * carries its own — so every property is answered 200; a failing propstat
   * would turn a save that already succeeded into an error dialog.
   */
  async #proppatch(req, res, account, segments) {
    const body = await readBody(req)
    const wrapper = new Set(['propertyupdate', 'set', 'remove', 'prop'])
    const names = [...body.matchAll(/<(?:\w+:)?([A-Za-z][\w.-]*)[\s/>]/g)]
      .map((match) => match[1])
      .filter((name) => !wrapper.has(name.toLowerCase()))

    const href = this.#href(account, segments, false)
    const props = names.map((name) => `<D:${name}/>`).join('')
    const xml = `${XML_HEAD}<D:multistatus xmlns:D="DAV:"><D:response><D:href>${escapeXml(
      href,
    )}</D:href><D:propstat><D:prop>${props}</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>`

    const payload = Buffer.from(xml, 'utf8')
    res.writeHead(207, {
      'Content-Type': 'text/xml; charset="utf-8"',
      'Content-Length': payload.length,
    })
    res.end(payload)
  }

  /**
   * Drop warm containers, narrowed by share and by library.
   *
   * Called when unlock state changed, a profile switched, or a share was edited
   * — anything that makes a `key` mean something else than it did. Keys start
   * with `accountId|lib|…`, so two prefix tests are all it takes.
   */
  #dropSessions({ account = '', lib = '' } = {}) {
    for (const [key, entry] of [...this.sessions]) {
      const [keyAccount, keyLib] = key.split('|')
      if (account && keyAccount !== account) continue
      if (lib && keyLib !== lib) continue
      this.sessions.delete(key)
      this.registry.close(entry.id).catch(() => {})
    }
  }
}
