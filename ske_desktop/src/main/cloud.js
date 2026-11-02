/**
 * Cloud browsing service.
 *
 * Everything that touches credentials lives here, in the main process:
 * the backend token (AList or OpenList), the derived name key, and the media
 * password. The renderer
 * only ever receives plaintext file names and ready-to-use stream URLs, so a
 * compromised renderer cannot read a password out of `window`.
 *
 * That is a deliberate difference from the web client, where the password sits
 * in `sessionStorage` because the Service Worker needs it.
 */
import crypto from 'node:crypto'
import { AListClient } from './alist.js'
import { t } from './i18n.js'
import { OpenListClient } from './openlist.js'
import {
  checkName,
  decryptStoredName,
  deriveNameKey,
  encryptName,
  isEncryptedName,
  tryDecryptName,
} from './ske-names.js'
import { HttpSource } from './sources.js'
import { looksLikeContainer, readThumbnail, PROBE_SIZE } from './ske-format.js'
import { deriveThumbKey } from './thumb-cache.js'

const VIDEO = new Set([
  'mp4', 'm4v', 'mkv', 'mov', 'avi', 'webm', 'ts', 'm2ts', 'flv', 'wmv', 'mpg', 'mpeg', '3gp',
])
const AUDIO = new Set([
  'mp3', 'flac', 'm4a', 'aac', 'wav', 'ogg', 'opus', 'wma', 'ape', 'alac',
])
const IMAGE = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'avif', 'heic', 'heif'])

const RAW_URL_TTL_MS = 5 * 60_000
const THUMB_CACHE_LIMIT = 300
/**
 * How long a storage-generated preview link is reused before asking again.
 *
 * These are signed CDN URLs (typically valid for a day), so re-resolving every
 * few minutes would spend a request per tile for nothing; a stale one is
 * re-resolved on demand when the fetch comes back 4xx.
 */
const REMOTE_THUMB_TTL_MS = 30 * 60_000

// Natural order, folder-first — the same rule the Android client applies to
// decrypted names, so the two frontends show an identical listing.
const collator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })

/** Paths already reported by `storedDirOf`; see the warning there. */
const warnedPaths = new Set()

/**
 * Wording a storage uses for "this entry is not there".
 *
 * It matters because a WebDAV write has two requests behind one save: the
 * listing that decides where the bytes go, and the upload itself. A storage's
 * directory cache lags its objects by a beat, so both can be answering about a
 * tree that changed a second ago — and "not there" is then a stale answer, not
 * a failure.
 */
const MISSING = /不存在|not exist|no such file|not found|object not found/i

/** Whether an error means "the storage does not have it", not "the call failed". */
function isMissing(err) {
  return err?.code === 'ENOENT' || MISSING.test(String(err?.message || ''))
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

export function extensionOf(name = '') {
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
}

export function kindOf(name) {
  const ext = extensionOf(name)
  if (VIDEO.has(ext)) return 'video'
  if (AUDIO.has(ext)) return 'audio'
  if (IMAGE.has(ext)) return 'image'
  return 'other'
}

function joinPath(base, rel) {
  const left = (base || '').replace(/\/+$/, '')
  const right = rel.startsWith('/') ? rel : `/${rel}`
  return `${left}${right}`.replace(/\/{2,}/g, '/') || '/'
}

/** Split a plain display path into components, dropping empties. */
function splitPath(plainPath) {
  return String(plainPath || '')
    .replace(/\\/g, '/')
    .split('/')
    .filter(Boolean)
}

/** The folder a plain path lives in, spelled the way `list` keys it. */
function plainDir(plainPath) {
  const parts = splitPath(plainPath)
  parts.pop()
  return `/${parts.join('/')}`
}

/**
 * Whether a stored entry answers to a plain name.
 *
 * Both spellings are legitimate: this app writes encrypted tokens, while a tree
 * that arrived another way (created in the web UI, or before the app was pointed
 * at this storage) keeps plain names.
 */
function entryNameMatches(storedName, plainName, nameKey) {
  if (storedName === plainName) return true
  try {
    return decryptStoredName(storedName, nameKey) === plainName
  } catch {
    return false
  }
}

export class CloudService {
  /**
   * @param {object} [options]
   * @param {import('./thumb-cache.js').ThumbDiskCache} [options.thumbCache]
   *   Encrypted previews across restarts. Optional so tests and the CLI can run
   *   without touching the disk.
   */
  constructor({ thumbCache = null } = {}) {
    this.sessions = new Map()
    this.thumbCache = thumbCache
  }

  /**
   * Log in and open a browsing session.
   *
   * The backend comes from the profile, so the renderer never has to say which
   * one to talk to. `otpCode` only matters for accounts with 2FA — both AList
   * and OpenList support it; a missing code surfaces as an error carrying
   * `needsOtp`.
   *
   * @param {{ profile: object, password: string, skePassword: string, otpCode?: string }} input
   */
  async login({ profile, password, skePassword, otpCode = '' }) {
    if (!profile?.server) throw new Error(t('errors.profileNoServer'))
    if (!skePassword) {
      throw new Error(t('errors.profileNoMediaPassword'))
    }

    const provider = profile.provider === 'openlist' ? 'openlist' : 'alist'
    const Client = provider === 'openlist' ? OpenListClient : AListClient
    const client = new Client({
      server: profile.server,
      username: profile.username,
      password,
      otpCode,
    })
    await client.login()

    const session = {
      id: crypto.randomBytes(8).toString('base64url'),
      profileId: profile.id,
      profileName: profile.name,
      provider,
      server: profile.server,
      basePath: profile.basePath || '',
      client,
      // The name key is derived once per login: PBKDF2 is deliberately slow.
      nameKey: deriveNameKey(skePassword),
      // Derived on the first preview request, not here: a login that never
      // opens a folder should not pay for a second PBKDF2.
      thumbKey: null,
      skePassword,
      rawUrls: new Map(),
      thumbs: new Map(),
      // storedPath -> storage-generated preview link for plain entries. Signed,
      // so it is kept with a timestamp and re-resolved when it goes stale.
      remoteThumbs: new Map(),
      // storedPath -> size+modified from the last listing, so a cached preview
      // is invalidated when the file behind it is replaced.
      stamps: new Map(),
      // plainPath -> storedPath, filled while listing. Needed because a file is
      // stored as `token.ske` while a folder is a bare `token`: re-encrypting a
      // plain path cannot know which one it is looking at.
      pathIndex: new Map(),
      // storedPath -> plaintext length for files written through WebDAV. The
      // listing reports the ciphertext size, which is ~0.1% larger; a client
      // that compares sizes after a copy would call the copy corrupt.
      plainSizes: new Map(),
      // Folders whose last listing predates a write made through this app.
      staleDirs: new Set(),
      createdAt: Date.now(),
    }

    this.sessions.set(session.id, session)
    return {
      sessionId: session.id,
      provider: session.provider,
      server: session.server,
      basePath: session.basePath,
    }
  }

  get(sessionId) {
    const session = this.sessions.get(sessionId)
    if (!session) throw new Error(t('errors.cloudSessionExpired'))
    return session
  }

  status(sessionId) {
    const session = this.sessions.get(sessionId)
    if (!session) return { connected: false }
    return {
      connected: true,
      sessionId: session.id,
      profileId: session.profileId,
      profileName: session.profileName,
      provider: session.provider,
      server: session.server,
      basePath: session.basePath,
    }
  }

  logout(sessionId) {
    this.sessions.delete(sessionId)
  }

  logoutAll() {
    this.sessions.clear()
  }

  /**
   * Plain display path -> stored path.
   *
   * The listing index wins. A folder reached by clicking was indexed with its
   * real stored path, and re-deriving it by re-encrypting every component only
   * works when *all* components are tokens — one plaintext folder name in the
   * tree (uploaded by hand, created by the web UI without encryption) made the
   * derived path point at nothing, so subfolders could not be opened. The web
   * client avoids this by navigating in stored space from the start.
   *
   * Encryption remains the fallback for paths no listing has produced, e.g. a
   * deep link straight into the tree.
   */
  /**
   * Stored path of one plain folder, walking the tree when the index is cold.
   *
   * Re-encrypting a path reproduces only the tree this app wrote: a folder that
   * came from somewhere else carries a plain name, and re-deriving it points at
   * a token folder that was never there. The storage then answers "no such file
   * or directory" for every path underneath — which is exactly how a save over
   * WebDAV failed into a folder created outside the app.
   *
   * Listing each level is what teaches the real names; the walk also fills the
   * index for the levels it passes, so a second write to the same folder costs
   * no extra request.
   */
  async storedDirOf(sessionId, plainPath = '/') {
    const session = this.get(sessionId)
    try {
      return await this.#walkStoredDir(session, plainPath)
    } catch (err) {
      // The index can outlive the folder it points at: a rename or delete made by
      // another client leaves a stored path that no longer exists, and every
      // write through it fails until the app restarts. Dropping the chain costs
      // one listing and turns a dead mount back into a working one.
      if (!isMissing(err)) throw err
      this.#forgetChain(session, plainPath)
      return await this.#walkStoredDir(session, plainPath)
    }
  }

  async #walkStoredDir(session, plainPath) {
    let stored = session.basePath
    let plain = ''

    for (const part of splitPath(plainPath)) {
      plain += `/${part}`
      const cached = session.pathIndex.get(plain)
      if (cached) {
        stored = cached
        continue
      }

      const entries = await session.client.list(stored)
      // Folders only: a file whose encrypted name happens to match would send
      // the caller into `<file>/…`, which the storage answers with "no such file
      // or directory" — the same wording as a genuinely missing folder.
      const hit = entries.find(
        (entry) => entry.is_dir && entryNameMatches(String(entry.name || ''), part, session.nameKey),
      )
      if (!hit) {
        // One line per path: a client retrying a dead folder (Explorer does, every
        // few seconds) must not turn the console into a wall of the same text.
        if (!warnedPaths.has(plain)) {
          warnedPaths.add(plain)
          // English on purpose: the Windows console these land in is not UTF-8,
          // and a mangled hint helps nobody.
          console.warn(`[dav] folder not found: ${plain} (${entries.length} entries in ${stored})`)
        }
        // Tagged, not just worded: callers have to tell "the folder is gone from
        // the index" from "the storage refused".
        throw Object.assign(new Error(t('errors.entryGone')), { code: 'ENOENT' })
      }

      stored = joinPath(stored, String(hit.name))
      session.pathIndex.set(plain, stored)
    }

    return stored
  }

  toStoredPath(session, plainPath) {
    const parts = splitPath(plainPath)
    const indexed = session.pathIndex.get(`/${parts.join('/')}`)
    if (indexed) return indexed

    const encrypted = parts.map((part) => encryptName(part, session.nameKey)).join('/')
    return joinPath(session.basePath, encrypted)
  }

  /**
   * Stored path of a *file*.
   *
   * The index is authoritative when the entry came from a listing. The `.ske`
   * fallback covers direct navigation (e.g. a deep link) where no listing ran:
   * every file in the tree is stored with that suffix.
   */
  storedPathForFile(session, plainPath) {
    const indexed = session.pathIndex.get(plainPath)
    if (indexed) return indexed
    return `${this.toStoredPath(session, plainPath)}.ske`
  }

  /**
   * Stored path of an entry the listing produced.
   *
   * File-manager writes must never be derived by re-encrypting a display path:
   * a plain entry has no token, and a stale mapping would point at a sibling.
   */
  #storedEntry(session, plainPath) {
    const stored = session.pathIndex.get(plainPath)
    if (!stored) throw new Error(t('errors.entryGone'))
    return stored
  }

  /** Drop plain→stored mappings for a subtree that a write just invalidated. */
  #forgetIndex(session, plainPath) {
    const prefix = `${plainPath}/`
    for (const key of [...session.pathIndex.keys()]) {
      if (key === plainPath || key.startsWith(prefix)) session.pathIndex.delete(key)
    }
  }

  /**
   * Drop every ancestor mapping of one plain path.
   *
   * Narrower than `#forgetIndex` on purpose: `storedDirOf` repairs a chain, and
   * a chain is exactly the mappings above the folder that failed.
   */
  #forgetChain(session, plainPath) {
    let plain = ''
    for (const part of splitPath(plainPath)) {
      plain += `/${part}`
      session.pathIndex.delete(plain)
    }
  }

  /**
   * Everything an upload needs for one folder.
   *
   * Kept here so the renderer never sees the media password or the name key: the
   * upload queue receives them in-process, uses them, and clears them when the
   * job ends.
   */
  uploadTarget(sessionId, plainPath) {
    const session = this.get(sessionId)
    return {
      client: session.client,
      dir: this.toStoredPath(session, plainPath),
      password: session.skePassword,
      nameKey: session.nameKey,
      // Which profile the server-side task belongs to: after a restart the
      // session is gone, and only the profile can log back in to claim it.
      profileId: session.profileId,
    }
  }

  /**
   * Write one already-encrypted container over `plainPath`.
   *
   * A save from a file manager arrives as plaintext that was encrypted on the
   * way in, so this is the only place a write lands. The stored name is derived
   * from the plaintext one, which makes an older entry with the same name — a
   * plain upload, or a container from a previous token — a *second* row in every
   * listing. Those are cleaned up *after* the upload succeeds: deleting them
   * first meant a failed upload left the folder empty, which is the worst of the
   * two outcomes for a save.
   *
   * @param {string} sessionId
   * @param {string} plainPath full display path of the file
   * @param {string} filePath the finished container on disk
   * @param {{ plainSize?: number }} [options] length the client sent, for size checks
   */
  async storeContainer(sessionId, plainPath, filePath, { plainSize = 0 } = {}) {
    const session = this.get(sessionId)
    const parts = splitPath(plainPath)
    const name = checkName(parts.pop() || '', t('errors.labelName'))
    const dirPlain = `/${parts.join('/')}`
    const plainKey = `/${[...parts, name].join('/')}`

    // The folder itself first: its real stored name may be a plain one, and
    // `list(dirPlain)` cannot learn it — that writes the folder's *children*
    // into the index, never the folder.
    const storedDir = await this.storedDirOf(sessionId, dirPlain)
    const storedPath = joinPath(storedDir, `${encryptName(name, session.nameKey)}.ske`)

    // What already answers to this name. The listing is forced, because this is
    // the question the storage's own cache answers wrong: it still shows a row
    // for a file that was deleted outside the app, which is how an empty folder
    // ends up replying "there is already a file with that name".
    const answering = await this.#answeringEntries(session, dirPlain, name).catch(() => [])

    const upload = () => session.client.upload(storedPath, filePath)

    for (let attempt = 0; ; attempt += 1) {
      try {
        await upload()
        break
      } catch (err) {
        // `op.Put` resolves the destination folder from the storage's object
        // cache before a single byte is sent, and any listing that lands in
        // between empties that cache. A WebDAV client polls the folder it copies
        // into every few seconds, so the window is always open: the storage
        // answers "no such file or directory" for a folder that is plainly there.
        // A forced listing puts the folder back; the upload itself never started,
        // so retrying costs nothing but the request. A short backoff matters
        // more than the count: the poll that emptied the cache is over by then.
        if (attempt >= 3 || !isMissing(err)) {
          console.warn(`[dav] upload failed: ${storedPath} (${err.message})`)
          throw err
        }
        await sleep(150 * 2 ** attempt)
        await this.list(sessionId, dirPlain, { refresh: true }).catch(() => {})
      }
    }

    session.pathIndex.set(plainKey, storedPath)
    this.#forgetCaches(session, storedPath)
    // After the caches are dropped: the plaintext length describes the file that
    // is now there, so it must survive the invalidation above.
    if (plainSize > 0) session.plainSizes.set(storedPath, plainSize)

    for (const entry of answering) {
      if (entry.stored === storedPath) continue
      try {
        await session.client.remove(entry.dir, [entry.leaf])
        this.#forgetCaches(session, entry.stored)
      } catch (err) {
        // A row the cache kept after the storage dropped it: there is nothing to
        // delete, and failing the save over it would be absurd.
        if (!isMissing(err)) console.warn(`[dav] duplicate kept: ${entry.stored} (${err.message})`)
      }
    }

    // The listing the storage already has predates this file.
    session.staleDirs?.add(dirPlain)
    // `existed` decides 201 or 204 at the WebDAV layer: a replace is not a create.
    return { storedPath, existed: answering.length > 0 }
  }

  /**
   * Files in one folder that answer to a plain name.
   *
   * Both spellings count: this app writes `token.ske`, while a tree that arrived
   * another way keeps plain names. Kept as `{ dir, leaf }` rather than a full
   * path so the caller can delete without re-parsing.
   */
  async #answeringEntries(session, dirPlain, name) {
    const listing = await this.list(session.id, dirPlain, { refresh: true })
    const dir = await this.storedDirOf(session.id, dirPlain)
    return listing.entries
      .filter((entry) => !entry.isDir && entry.name === name)
      .map((entry) => ({ stored: joinPath(dir, entry.storedName), dir, leaf: entry.storedName }))
  }

  /** Signed URLs and previews for a path that was renamed or deleted. */
  #forgetCaches(session, storedPath) {
    session.rawUrls.delete(storedPath)
    session.thumbs.delete(storedPath)
    session.remoteThumbs.delete(storedPath)
    session.stamps.delete(storedPath)
    session.plainSizes?.delete(storedPath)
  }

  /**
   * Create a folder under `plainPath`.
   *
   * `encrypt` mirrors the web dialog's default of encrypting the name: folders
   * are bare tokens in the tree, so a plain folder name would be readable by
   * anyone browsing the storage. Unticking it is for trees shared with clients
   * that cannot decrypt.
   */
  async makeDir(sessionId, plainPath, name, { encrypt = true } = {}) {
    const session = this.get(sessionId)
    const trimmed = checkName(name, t('errors.labelFolder'))
    const storedName = encrypt ? encryptName(trimmed, session.nameKey) : trimmed
    await session.client.mkdir(joinPath(this.toStoredPath(session, plainPath), storedName))
    session.staleDirs?.add(plainPath)
    return { name: trimmed, storedName }
  }

  /**
   * Rename a listed entry, preserving whether its name is encrypted.
   *
   * A plain file must not acquire an encrypted name: the listing would tag it
   * 加密 even though its bytes are still plain. A container keeps its `.ske`
   * marker, which is the only signal left when its name cannot be decrypted.
   */
  async rename(sessionId, plainPath, newName) {
    const session = this.get(sessionId)
    const trimmed = checkName(newName, t('errors.labelName'))

    const storedPath = this.#storedEntry(session, plainPath)
    const storedName = storedPath.slice(storedPath.lastIndexOf('/') + 1)
    const container = isEncryptedName(storedName)
    const encrypted = container || tryDecryptName(storedName, session.nameKey) !== null

    const nextName = encrypted
      ? `${encryptName(trimmed, session.nameKey)}${container ? '.ske' : ''}`
      : trimmed

    await session.client.rename(storedPath, nextName)
    this.#forgetIndex(session, plainPath)
    this.#forgetCaches(session, storedPath)
    session.staleDirs?.add(plainDir(plainPath))
    return { name: trimmed, storedName: nextName }
  }

  /** Delete a listed entry. The stored path comes from the index, never rebuilt. */
  async remove(sessionId, plainPath) {
    const session = this.get(sessionId)
    const storedPath = this.#storedEntry(session, plainPath)
    const dir = storedPath.slice(0, storedPath.lastIndexOf('/')) || '/'
    const name = storedPath.slice(storedPath.lastIndexOf('/') + 1)

    try {
      await session.client.remove(dir, [name])
    } catch (err) {
      // The index is built from a listing, and a listing can carry a row the
      // storage has already dropped. Deleting something that is not there gives
      // the client exactly what it asked for; failing it makes the mount look
      // broken and keeps the phantom row alive for the next attempt.
      if (!isMissing(err)) throw err
    }
    this.#forgetIndex(session, plainPath)
    this.#forgetCaches(session, storedPath)
    session.staleDirs?.add(plainDir(plainPath))
    return { removed: true }
  }

  /**
   * Delete a file an upload produced, addressed the way the history knows it.
   *
   * A finished upload row keeps the plain folder and the stored leaf: a token
   * for an encrypted upload, the file's own name for a plain one. The folder is
   * derived exactly as [uploadTarget] derived it, so nothing has to be listed
   * first — the upload may be weeks old.
   */
  async removeUpload(sessionId, plainDir, storedName) {
    const session = this.get(sessionId)
    const folder = this.toStoredPath(session, plainDir || '/')
    const leaf = String(storedName || '').replace(/^\/+/, '')
    if (!leaf) throw new Error(t('errors.pathMissing'))

    try {
      await session.client.remove(folder, [leaf])
    } catch (err) {
      // Same reasoning as [remove]: a row that is already gone is a success.
      if (!isMissing(err)) throw err
    }
    this.#forgetCaches(session, joinPath(folder, leaf))
    session.staleDirs?.add(plainDir || '/')
    return { removed: true }
  }

  /**
   * List one directory.
   *
   * Names are decrypted here, not in the renderer, so the renderer never holds
   * the name key. Entries that cannot be decrypted are surfaced as `locked`
   * instead of being hidden — a wrong password should be visible, not silent.
   * Plain (unencrypted) entries are listed too, with `encrypted: false`: they
   * play without a password, and the UI marks the difference.
   */
  async list(sessionId, plainPath = '/', { refresh = false } = {}) {
    const session = this.get(sessionId)
    const storedPath = this.toStoredPath(session, plainPath)
    // `refresh` is the web client's manual-refresh flag: it tells AList to drop
    // its own directory cache, so files changed elsewhere show up immediately
    // instead of after the storage driver's cache expires.
    //
    // A write marks its folder stale, because the storage's cached listing is
    // older than the file just written: without this one forced refresh the new
    // entry stays invisible to both a re-listing and a GET.
    const stale = session.staleDirs?.delete(plainPath) === true
    const raw = await session.client.list(storedPath, { refresh: refresh || stale })

    const entries = raw.map((entry) => {
      const storedName = String(entry.name || '')
      let name = storedName
      let decrypted = false

      try {
        name = decryptStoredName(storedName, session.nameKey)
        decrypted = true
      } catch {
        // Not an encrypted name (or a different password): keep it visible.
      }

      // A name that decrypts proves the entry is encrypted. Plain entries carry
      // no marker at all, so the `.ske` suffix is the only fallback — the same
      // rule the web client tags entries with.
      const encrypted = decrypted || isEncryptedName(storedName)

      // Always leading-slash normalised: the renderer round-trips these back as
      // plain paths, and a miss here would silently re-encrypt with `.ske`.
      const plain = `/${[...splitPath(plainPath), name].join('/')}`
      const stored = joinPath(storedPath, storedName)
      session.pathIndex.set(plain, stored)
      if (!entry.is_dir) {
        session.stamps.set(stored, `${Number(entry.size) || 0}:${entry.modified || ''}`)
      }

      // A plain entry can be previewed by the storage itself. A container never
      // can: the server only sees ciphertext, so its preview comes from the
      // encrypted envelope instead.
      const remoteThumb = !entry.is_dir && !encrypted ? String(entry.thumb || '') : ''
      if (remoteThumb) {
        session.remoteThumbs.set(stored, { url: remoteThumb, at: Date.now() })
      }

      // A container is stored larger than the plaintext it holds. When this
      // session wrote the file it knows the real length, and anything that
      // compares sizes — Explorer right after a copy, the grid's size column —
      // should see that instead of the padding.
      const plainSize = entry.is_dir ? 0 : session.plainSizes?.get(stored)

      return {
        name,
        storedName,
        isDir: !!entry.is_dir,
        size: plainSize ?? (Number(entry.size) || 0),
        modified: entry.modified || '',
        encrypted,
        locked: !decrypted && isEncryptedName(storedName),
        kind: entry.is_dir ? 'dir' : kindOf(name),
        path: plain,
        hasThumb: !!remoteThumb,
      }
    })

    entries.sort((a, b) => {
      if (a.isDir !== b.isDir) return a.isDir ? -1 : 1
      return collator.compare(a.name, b.name)
    })

    return {
      path: `/${splitPath(plainPath).join('/')}`,
      entries,
      total: entries.length,
    }
  }

  /** Resolve a file to a signed direct URL, cached briefly (links expire). */
  async resolve(sessionId, plainPath) {
    const session = this.get(sessionId)
    const storedPath = this.storedPathForFile(session, plainPath)

    const cached = session.rawUrls.get(storedPath)
    if (cached && Date.now() - cached.at < RAW_URL_TTL_MS) {
      return { rawUrl: cached.rawUrl, size: cached.size, storedPath }
    }

    const info = await session.client.getFile(storedPath)
    if (!info.rawUrl) throw new Error(t('errors.noRawUrl'))

    session.rawUrls.set(storedPath, { ...info, at: Date.now() })
    return { rawUrl: info.rawUrl, size: info.size, storedPath }
  }

  /**
   * Preview URL for an unencrypted entry, as the storage produced it.
   *
   * `/api/fs/list` already hands one out per entry, so browsing a folder of
   * photos costs no extra request at all. The map is refreshed from
   * `/api/fs/get` only when the cached link is stale, which is why `refresh`
   * exists: a signed link can expire while the app stays open.
   *
   * @returns {Promise<string|null>} null when the storage has no preview
   */
  async remoteThumbUrl(sessionId, plainPath, { refresh = false } = {}) {
    const session = this.get(sessionId)
    const storedPath = this.storedPathForFile(session, plainPath)

    const cached = session.remoteThumbs.get(storedPath)
    if (!refresh && cached && Date.now() - cached.at < REMOTE_THUMB_TTL_MS) {
      return cached.url || null
    }

    const info = await session.client.getFile(storedPath)
    const url = info.thumb || (refresh ? '' : cached?.url || '')
    if (url) session.remoteThumbs.set(storedPath, { url, at: Date.now() })
    else session.remoteThumbs.delete(storedPath)
    return url || null
  }

  /**
   * Decrypt just the preview envelope (≈40 KB), never a 1 MiB block.
   * Returns null when the container has no preview.
   *
   * Three layers, cheapest first: this login's decrypted map, then the encrypted
   * on-disk cache (a hit skips both the range request and the AES work), then
   * the network. Only the 512 px envelope is ever stored on disk, sealed with a
   * key derived from the media password — see `thumb-cache.js`.
   */
  async thumbnail(sessionId, plainPath) {
    const session = this.get(sessionId)
    const storedPath = this.storedPathForFile(session, plainPath)

    const cached = session.thumbs.get(storedPath)
    if (cached) {
      // Refresh LRU position.
      session.thumbs.delete(storedPath)
      session.thumbs.set(storedPath, cached)
      return cached
    }

    // Only a listed entry carries a stamp, and only a stamp can tell a replaced
    // file apart from the one an older cached picture was made for. A path that
    // no listing produced (a deep link) skips the disk cache rather than risk
    // showing the previous picture.
    const stamp = session.stamps.get(storedPath)
    const cacheName = stamp ? this.thumbCache?.keyFor(session.profileId, storedPath, stamp) : null

    if (cacheName) {
      const sealed = await this.thumbCache.get(cacheName, this.#thumbKeyFor(session))
      if (sealed) {
        this.#remember(session, storedPath, sealed)
        return sealed
      }
    }

    const { rawUrl } = await this.resolve(sessionId, plainPath)
    const source = new HttpSource(rawUrl)
    await source.open()

    let jpeg = null
    try {
      // Only containers carry a preview envelope. A plain image has none, so the
      // UI falls back to the original file (or a captured video frame), exactly
      // like the web client does for unencrypted entries.
      const probe = await source.read(0, PROBE_SIZE)
      if (looksLikeContainer(probe)) {
        jpeg = await readThumbnail(source, session.skePassword, probe)
      }
    } finally {
      await source.close().catch(() => {})
    }

    if (jpeg) {
      this.#remember(session, storedPath, jpeg)
      // Fire and forget: a tile must not wait on a disk write, and a failed one
      // only costs the next launch a single range request.
      if (cacheName) {
        void this.thumbCache.put(cacheName, jpeg, this.#thumbKeyFor(session)).catch(() => {})
      }
    }
    return jpeg
  }

  /**
   * Key for the on-disk preview cache, derived once per login.
   *
   * A different salt from the name key on purpose: a leaked preview blob must not
   * help decrypt the names (or vice versa), even though both start from the same
   * password.
   */
  #thumbKeyFor(session) {
    session.thumbKey ??= deriveThumbKey(session.skePassword)
    return session.thumbKey
  }

  /** Decrypted previews for one login, bounded and evicted oldest-first. */
  #remember(session, storedPath, jpeg) {
    session.thumbs.delete(storedPath)
    session.thumbs.set(storedPath, jpeg)
    while (session.thumbs.size > THUMB_CACHE_LIMIT) {
      session.thumbs.delete(session.thumbs.keys().next().value)
    }
  }

  /**
   * What the cloud sessions are holding right now.
   *
   * `previewBytes` is the decrypted JPEG payload (≈40 KB each), `links` counts
   * signed URLs waiting for their short TTL. Both are per login and vanish with
   * the session. `thumbDisk` is the encrypted cache, which survives restarts.
   */
  stats() {
    let previews = 0
    let previewBytes = 0
    let links = 0
    for (const session of this.sessions.values()) {
      previews += session.thumbs.size
      for (const jpeg of session.thumbs.values()) previewBytes += jpeg.length
      links += session.rawUrls.size
    }
    return {
      sessions: this.sessions.size,
      previews,
      previewBytes,
      links,
      thumbDisk: this.thumbCache?.stats() ?? { count: 0, bytes: 0 },
    }
  }

  /**
   * Drop decrypted previews, in memory and on disk.
   *
   * Safe at any time: the next tile request re-pulls and re-decrypts. Killing
   * the encrypted copies costs the next launch speed, nothing else.
   */
  async clearPreviews() {
    for (const session of this.sessions.values()) session.thumbs.clear()
    await this.thumbCache?.clear()
  }

  /**
   * Re-apply the preview-cache settings right after they changed.
   *
   * Turning the cache off deletes what is already on disk instead of merely
   * stopping new writes — otherwise the switch would look like it did nothing.
   * A smaller limit frees space now rather than at the next launch.
   */
  async syncThumbCache() {
    if (!this.thumbCache) return
    if (this.thumbCache.enabled) await this.thumbCache.trim()
    else await this.thumbCache.clear()
  }

  /** Drop cached signed URLs; the next resolve asks the server again. */
  clearLinks() {
    for (const session of this.sessions.values()) {
      session.rawUrls.clear()
      session.remoteThumbs.clear()
    }
  }
}
