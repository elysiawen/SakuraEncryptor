/**
 * Local library: real file-system browsing with decrypted names.
 *
 * Uses plain `fs` rather than the browser's File System Access API. On desktop
 * that API is a downgrade — it needs re-authorisation across restarts and gives
 * no stable path. Here a directory is just a path the user picked once.
 *
 * The local password is independent of any cloud profile, matching the Android
 * client: someone who only has local files never has to configure AList.
 */
import fsp from 'node:fs/promises'
import path from 'node:path'
import {
  checkName,
  decryptStoredName,
  deriveNameKey,
  encryptName,
  isEncryptedName,
  tryDecryptName,
} from './ske-names.js'

const VIDEO = new Set([
  'mp4', 'm4v', 'mkv', 'mov', 'avi', 'webm', 'ts', 'm2ts', 'flv', 'wmv', 'mpg', 'mpeg', '3gp',
])
const AUDIO = new Set([
  'mp3', 'flac', 'm4a', 'aac', 'wav', 'ogg', 'opus', 'wma', 'ape', 'alac',
])
const IMAGE = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'avif', 'heic', 'heif'])

const collator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })

function kindOf(name, ext) {
  const lower = ext || name.slice(name.lastIndexOf('.') + 1).toLowerCase()
  if (VIDEO.has(lower)) return 'video'
  if (AUDIO.has(lower)) return 'audio'
  if (IMAGE.has(lower)) return 'image'
  return 'other'
}

export class LocalLibrary {
  constructor() {
    this.root = ''
    this.password = ''
    this.nameKey = null
    // plainPath -> absolute path. Same reason as the cloud service: a folder on
    // disk is a bare token while a file is `token.ske`, so re-encrypting a plain
    // path cannot reconstruct an existing path on its own.
    this.pathIndex = new Map()
  }

  /** Remember the local password for this session (never persisted to disk). */
  unlock(password) {
    if (!password) throw new Error('密码不能为空')
    this.password = password
    this.nameKey = deriveNameKey(password)
    return { unlocked: true }
  }

  lock() {
    this.password = ''
    this.nameKey = null
    this.root = ''
    this.pathIndex.clear()
    return { unlocked: false }
  }

  get unlocked() {
    return !!this.nameKey
  }

  setRoot(dir) {
    this.root = dir ? path.resolve(dir) : ''
    this.pathIndex.clear()
    return { root: this.root }
  }

  status() {
    return { unlocked: this.unlocked, root: this.root }
  }

  /**
   * Absolute path for a *display* path.
   *
   * Display paths are plaintext; the tree on disk is encrypted per component.
   * The index wins when the entry came from a listing (it also covers plain,
   * unencrypted files a user dropped in by hand); otherwise each component is
   * encrypted, which is exactly how the writer laid the tree out.
   */
  resolveInside(relPath = '/') {
    if (!this.root) throw new Error('尚未选择本地目录')

    const parts = String(relPath).replace(/\\/g, '/').split('/').filter(Boolean)
    // Reject traversal segments outright rather than normalising them away.
    if (parts.some((part) => part === '..' || part === '.')) {
      throw new Error('非法路径')
    }

    const key = `/${parts.join('/')}`
    const indexed = this.pathIndex.get(key)
    if (indexed) return indexed

    if (!parts.length) return this.root

    const encrypted = parts.map((part) => encryptName(part, this.nameKey))
    const absolute = path.resolve(this.root, ...encrypted)
    if (absolute !== this.root && !absolute.startsWith(this.root + path.sep)) {
      throw new Error('非法路径')
    }
    return absolute
  }

  /**
   * List a directory with decrypted names.
   *
   * Folders are stored as bare tokens, files as `token.ske`. Anything that does
   * not decrypt is shown as-is with a marker, so a wrong password is visible
   * instead of producing an empty-looking folder.
   */
  async list(relPath = '/') {
    if (!this.unlocked) throw new Error('本地库尚未解锁')
    const absolute = this.resolveInside(relPath)
    const dirents = await fsp.readdir(absolute, { withFileTypes: true })

    const entries = []
    for (const dirent of dirents) {
      const full = path.join(absolute, dirent.name)
      let size = 0
      if (dirent.isFile()) {
        const stat = await fsp.stat(full).catch(() => null)
        size = stat?.size || 0
      }

      let name = dirent.name
      let decrypted = false
      try {
        name = decryptStoredName(dirent.name, this.nameKey)
        decrypted = true
      } catch {
        // Not encrypted, or a different password.
      }

      // The `.ske` marker lives on the stored name; a decrypted name never
      // keeps it. Folders are bare tokens, so decrypting is their only marker.
      const isSke = isEncryptedName(dirent.name)
      const plain = `/${[...String(relPath).split('/').filter(Boolean), name].join('/')}`
      this.pathIndex.set(plain, full)

      entries.push({
        name,
        storedName: dirent.name,
        isDir: dirent.isDirectory(),
        size,
        locked: !dirent.isDirectory() && isSke && !decrypted,
        encrypted: decrypted || isSke,
        kind: dirent.isDirectory() ? 'dir' : kindOf(name, ''),
        path: plain,
      })
    }

    entries.sort((a, b) => {
      if (a.isDir !== b.isDir) return a.isDir ? -1 : 1
      return collator.compare(a.name, b.name)
    })

    return {
      path: `/${String(relPath).split('/').filter(Boolean).join('/')}`,
      root: this.root,
      entries,
      total: entries.length,
    }
  }

  /** Drop plain→absolute mappings for a subtree a write just invalidated. */
  #forget(plainPath) {
    const prefix = `${plainPath}/`
    for (const key of [...this.pathIndex.keys()]) {
      if (key === plainPath || key.startsWith(prefix)) this.pathIndex.delete(key)
    }
  }

  /** Create a folder inside `relDir`; `encrypt` turns its name into a token too. */
  async makeDir(relDir, name, { encrypt = true } = {}) {
    if (!this.unlocked) throw new Error('本地库尚未解锁')
    const dir = this.resolveInside(relDir)
    const trimmed = checkName(name, '文件夹名称')
    const storedName = encrypt ? encryptName(trimmed, this.nameKey) : trimmed
    try {
      await fsp.mkdir(path.join(dir, storedName))
    } catch (err) {
      if (err.code === 'EEXIST') throw new Error('同名文件夹已存在')
      throw err
    }
    return { name: trimmed, storedName }
  }

  /**
   * Rename in place, keeping the entry as encrypted (or plain) as it was.
   *
   * Same rule as the cloud library: a plain file must not gain an encrypted
   * name, and a container must keep its `.ske` marker.
   */
  async rename(relPath, newName) {
    if (!this.unlocked) throw new Error('本地库尚未解锁')
    const absolute = this.resolveInside(relPath)
    const trimmed = checkName(newName, '名称')

    const storedName = path.basename(absolute)
    const container = isEncryptedName(storedName)
    const encrypted = container || tryDecryptName(storedName, this.nameKey) !== null
    const nextName = encrypted
      ? `${encryptName(trimmed, this.nameKey)}${container ? '.ske' : ''}`
      : trimmed

    const target = path.join(path.dirname(absolute), nextName)
    // `fs.rename` replaces silently on POSIX; refuse instead of losing a file.
    if (target !== absolute) {
      const taken = await fsp.access(target).then(
        () => true,
        () => false,
      )
      if (taken) throw new Error('同名条目已存在')
    }

    await fsp.rename(absolute, target)
    this.#forget(relPath)
    return { name: trimmed, storedName: nextName }
  }

  /** Delete an entry from disk. There is no trash, so the UI confirms first. */
  async remove(relPath) {
    if (!this.unlocked) throw new Error('本地库尚未解锁')
    const absolute = this.resolveInside(relPath)
    // `resolveInside` already rejects traversal; this only protects the root.
    if (absolute === this.root) throw new Error('不能删除库根目录')

    await fsp.rm(absolute, { recursive: true, force: false })
    this.#forget(relPath)
    return { removed: true }
  }

  /** Absolute path of a listed file, for the media registry. */
  filePath(relPath) {
    return this.resolveInside(relPath)
  }

  /** Output path for encrypting a file that lives at `relPath`. */
  siblingOutputPath(relPath) {
    const absolute = this.resolveInside(relPath)
    return path.join(path.dirname(absolute), `${encryptName(path.basename(absolute), this.nameKey)}.ske`)
  }
}
