/**
 * Cloud browsing service.
 *
 * Everything that touches credentials lives here, in the main process:
 * the AList token, the derived name key, and the media password. The renderer
 * only ever receives plaintext file names and ready-to-use stream URLs, so a
 * compromised renderer cannot read a password out of `window`.
 *
 * That is a deliberate difference from the web client, where the password sits
 * in `sessionStorage` because the Service Worker needs it.
 */
import crypto from 'node:crypto'
import { AListClient } from './alist.js'
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

// Natural order, folder-first — the same rule the Android client applies to
// decrypted names, so the two frontends show an identical listing.
const collator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })

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
   * @param {{ profile: object, password: string, skePassword: string }} input
   */
  async login({ profile, password, skePassword }) {
    if (!profile?.server) throw new Error('配置缺少服务器地址')
    if (!skePassword) {
      throw new Error('该配置还没有媒体加密密码，请先在配置里填写')
    }

    const client = new AListClient({
      server: profile.server,
      username: profile.username,
      password,
    })
    await client.login()

    const session = {
      id: crypto.randomBytes(8).toString('base64url'),
      profileId: profile.id,
      profileName: profile.name,
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
      // storedPath -> size+modified from the last listing, so a cached preview
      // is invalidated when the file behind it is replaced.
      stamps: new Map(),
      // plainPath -> storedPath, filled while listing. Needed because a file is
      // stored as `token.ske` while a folder is a bare `token`: re-encrypting a
      // plain path cannot know which one it is looking at.
      pathIndex: new Map(),
      createdAt: Date.now(),
    }

    this.sessions.set(session.id, session)
    return { sessionId: session.id, server: session.server, basePath: session.basePath }
  }

  get(sessionId) {
    const session = this.sessions.get(sessionId)
    if (!session) throw new Error('云端会话已失效，请重新连接')
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
    if (!stored) throw new Error('该条目已不在当前列表中，请刷新后重试')
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
    }
  }

  /** Signed URLs and previews for a path that was renamed or deleted. */
  #forgetCaches(session, storedPath) {
    session.rawUrls.delete(storedPath)
    session.thumbs.delete(storedPath)
    session.stamps.delete(storedPath)
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
    const trimmed = checkName(name, '文件夹名称')
    const storedName = encrypt ? encryptName(trimmed, session.nameKey) : trimmed
    await session.client.mkdir(joinPath(this.toStoredPath(session, plainPath), storedName))
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
    const trimmed = checkName(newName, '名称')

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
    return { name: trimmed, storedName: nextName }
  }

  /** Delete a listed entry. The stored path comes from the index, never rebuilt. */
  async remove(sessionId, plainPath) {
    const session = this.get(sessionId)
    const storedPath = this.#storedEntry(session, plainPath)
    const dir = storedPath.slice(0, storedPath.lastIndexOf('/')) || '/'
    const name = storedPath.slice(storedPath.lastIndexOf('/') + 1)

    await session.client.remove(dir, [name])
    this.#forgetIndex(session, plainPath)
    this.#forgetCaches(session, storedPath)
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
  async list(sessionId, plainPath = '/') {
    const session = this.get(sessionId)
    const storedPath = this.toStoredPath(session, plainPath)
    const raw = await session.client.list(storedPath)

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

      return {
        name,
        storedName,
        isDir: !!entry.is_dir,
        size: Number(entry.size) || 0,
        modified: entry.modified || '',
        encrypted,
        locked: !decrypted && isEncryptedName(storedName),
        kind: entry.is_dir ? 'dir' : kindOf(name),
        path: plain,
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
    if (!info.rawUrl) throw new Error('服务器没有返回下载地址')

    session.rawUrls.set(storedPath, { ...info, at: Date.now() })
    return { rawUrl: info.rawUrl, size: info.size, storedPath }
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
    for (const session of this.sessions.values()) session.rawUrls.clear()
  }
}
