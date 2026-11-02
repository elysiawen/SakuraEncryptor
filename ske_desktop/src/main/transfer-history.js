/**
 * Finished transfers, kept across restarts.
 *
 * Only rows that already settled are written. A job that was still running when
 * the app closed cannot be resumed, so restoring it would put a progress bar on
 * screen that never moves again — the honest thing is for it to be gone.
 *
 * What is stored is exactly what the transfers page renders, including paths in
 * the clear. Those are the user's own library layout (the same material
 * `settings.json` already holds), they never leave this machine, and encrypting
 * them would only mean losing the history when the keychain is reset. No
 * credential ever reaches here: the queues hand over an explicit record rather
 * than a job object, so a password added to a job later cannot leak by accident.
 *
 * Writes are small (a few hundred rows), rare (once per finished job) and
 * synchronous, because the one write that matters most is the last one — on a
 * quit that gives an async write no chance to land.
 */
import fs from 'node:fs'
import path from 'node:path'

const HISTORY_VERSION = 1

/** How many rows to keep. Old ones are dropped, newest first. */
const DEFAULT_LIMIT = 300

/** Coalesce a burst of finishes (a batch upload ends row after row). */
const SAVE_DELAY_MS = 400

/** Statuses that mean a job will never change again. */
const SETTLED = new Set(['done', 'failed', 'cancelled'])

function isRecord(value) {
  return Boolean(value && typeof value === 'object' && value.id && value.kind)
}

export class TransferHistory {
  /**
   * @param {object} options
   * @param {string} options.filePath where the JSON lives (`userData/transfers.json`)
   * @param {number} [options.limit] rows to keep across both kinds
   */
  constructor({ filePath, limit = DEFAULT_LIMIT } = {}) {
    if (!filePath) throw new Error('TransferHistory needs a file path')
    this.filePath = filePath
    this.limit = Math.max(1, limit)
    this.records = []
    this.saveTimer = null
    this.stopped = false
  }

  /**
   * Read the history from disk.
   *
   * A corrupt file is not worth failing a launch over: the app starts with an
   * empty history and the bad file is kept next to it for support.
   */
  async load() {
    let raw
    try {
      raw = await fs.promises.readFile(this.filePath, 'utf8')
    } catch (err) {
      if (err.code !== 'ENOENT') {
        console.error(`[transfers] cannot read ${this.filePath}:`, err.message)
      }
      this.records = []
      return this
    }

    try {
      const parsed = JSON.parse(raw)
      const list = Array.isArray(parsed) ? parsed : parsed?.records
      this.records = Array.isArray(list) ? list.filter(isRecord) : []
    } catch (err) {
      console.error(`[transfers] cannot parse ${this.filePath}:`, err.message)
      await fs.promises
        .rename(this.filePath, `${this.filePath}.corrupt-${Date.now()}`)
        .catch(() => {})
      this.records = []
    }

    return this
  }

  /** Records of one kind, newest first. */
  recordsFor(kind) {
    return this.records.filter((record) => record.kind === kind)
  }

  /**
   * Add or replace one settled row.
   *
   * Replacing by `kind + id` matters for cancel-then-fail sequences and for a
   * retried download that reuses the same row.
   */
  remember(record) {
    if (this.stopped) return
    if (!isRecord(record) || !SETTLED.has(record.status)) return
    const index = this.records.findIndex(
      (item) => item.kind === record.kind && item.id === record.id,
    )
    if (index >= 0) this.records[index] = record
    else this.records.unshift(record)

    this.records.sort((a, b) => (b.finishedAt || 0) - (a.finishedAt || 0))
    if (this.records.length > this.limit) this.records.length = this.limit

    this.#scheduleSave()
  }

  /**
   * Drop rows the user cleared from the transfers page.
   *
   * Scoped to one kind for the same reason [remember] is: the two queues mint
   * ids independently, so an id alone does not name a row.
   */
  forget(kind, ids) {
    const gone = new Set(ids)
    const before = this.records.length
    this.records = this.records.filter(
      (record) => record.kind !== kind || !gone.has(record.id),
    )
    // Clearing is an explicit action: the file must agree before the UI does.
    if (this.records.length !== before) this.flush()
  }

  #scheduleSave() {
    if (this.saveTimer) return
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null
      this.flush()
    }, SAVE_DELAY_MS)
  }

  /**
   * Seal the history as the app goes down.
   *
   * Writes what is already recorded, then ignores everything after. Quitting
   * cancels the queues, and those rows never got their chance: recording them
   * as "cancelled" would read like the user's own decision on the next launch.
   */
  stop() {
    this.stopped = true
    this.flush()
  }

  /** Write now (used by [forget] and on quit). */
  flush() {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer)
      this.saveTimer = null
    }

    const tmp = `${this.filePath}.tmp`
    try {
      fs.mkdirSync(path.dirname(this.filePath), { recursive: true })
      fs.writeFileSync(tmp, JSON.stringify({ version: HISTORY_VERSION, records: this.records }, null, 2), 'utf8')
      fs.renameSync(tmp, this.filePath)
    } catch (err) {
      console.error(`[transfers] cannot write ${this.filePath}:`, err.message)
    }
  }
}
