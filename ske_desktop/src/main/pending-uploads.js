/**
 * Uploads that are in flight right now, kept on disk.
 *
 * A container handed to AList is not the same as a file in the cloud: the
 * server keeps writing after the request ends, and on a rate-limited storage
 * backend that write can outlive the app by many minutes. What makes it
 * recoverable is the task id AList hands back — with it, and the profile that
 * owns it, the next launch can look the job up, either to watch it finish or to
 * learn that it already did.
 *
 * A chunked upload (OpenList, large files) writes the other shape: `mode` is
 * `chunk`, there is no task id yet, and `payloadPath` points at the container
 * kept in `userData/upload-payloads`. That row is what lets the next launch
 * send the remaining chunks instead of forgetting the upload entirely.
 *
 * A separate file from `transfers.json` on purpose: that one is history, rows a
 * user can clear. This one is state — a row disappearing from it means the
 * upload was resolved, not that somebody tidied the list.
 *
 * Paths are stored in the clear, like every other file here (see
 * `transfer-history.js`); no credential is ever written. The server address is
 * not stored either: the profile id is enough to log back in, and a profile
 * that was deleted should make the row unresolvable rather than point the app
 * at some other server.
 */
import fs from 'node:fs'
import path from 'node:path'

const PENDING_VERSION = 1

/** A hard cap, so a crash loop cannot grow the file without bound. */
const DEFAULT_LIMIT = 200

/** Progress changes constantly; the file does not need to. */
const SAVE_DELAY_MS = 500

function isEntry(value) {
  return Boolean(value && typeof value === 'object' && value.id && value.profileId)
}

export class PendingUploads {
  /**
   * @param {object} options
   * @param {string} options.filePath where the JSON lives (`userData/pending-uploads.json`)
   * @param {number} [options.limit]
   */
  constructor({ filePath, limit = DEFAULT_LIMIT } = {}) {
    if (!filePath) throw new Error('PendingUploads needs a file path')
    this.filePath = filePath
    this.limit = Math.max(1, limit)
    this.records = []
    this.saveTimer = null
  }

  async load() {
    try {
      const raw = await fs.promises.readFile(this.filePath, 'utf8')
      const parsed = JSON.parse(raw)
      const list = Array.isArray(parsed?.records) ? parsed.records : []
      this.records = list.filter(isEntry)
    } catch (err) {
      if (err.code !== 'ENOENT') {
        console.error(`[pending] cannot read ${this.filePath}:`, err.message)
      }
      this.records = []
    }
    return this
  }

  entries() {
    return [...this.records]
  }

  /** Add a row, or replace the one with the same id. */
  upsert(entry) {
    if (!isEntry(entry)) return
    const index = this.records.findIndex((item) => item.id === entry.id)
    if (index >= 0) this.records[index] = entry
    else this.records.unshift(entry)
    if (this.records.length > this.limit) this.records.length = this.limit
    this.#scheduleSave()
  }

  remove(id) {
    const before = this.records.length
    this.records = this.records.filter((item) => item.id !== id)
    if (this.records.length !== before) this.#scheduleSave()
  }

  #scheduleSave() {
    if (this.saveTimer) return
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null
      this.flush()
    }, SAVE_DELAY_MS)
  }

  /**
   * Write now.
   *
   * Synchronous for the same reason as the history: the row that matters most
   * is the one written the instant before the app goes down.
   */
  flush() {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer)
      this.saveTimer = null
    }

    const tmp = `${this.filePath}.tmp`
    try {
      fs.mkdirSync(path.dirname(this.filePath), { recursive: true })
      fs.writeFileSync(tmp, JSON.stringify({ version: PENDING_VERSION, records: this.records }, null, 2), 'utf8')
      fs.renameSync(tmp, this.filePath)
    } catch (err) {
      console.error(`[pending] cannot write ${this.filePath}:`, err.message)
    }
  }
}
