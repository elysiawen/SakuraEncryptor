/**
 * useLyrics — find and read the sidecar `.lrc` sitting next to the playing track.
 *
 * The web client has no lyrics service, so the file name is the only link
 * between the audio and its lyrics. An exact `<track>.lrc` wins, then language
 * variants (`<track>.zh.lrc`), matching how the desktop client picks one.
 *
 * Reading goes through the same routes as subtitles: encrypted files use the
 * Service Worker proxy, plaintext local files use `/ske-local/`, and plaintext
 * remote files fall back to the provider's own `/d/` proxy when the direct
 * link is blocked by CORS.
 */
import { reactive } from 'vue'
import { getFileInfo, getServer } from './useRemoteStorage.js'
import { getNameKeyFromSession } from './useCrypto.js'
import { baseName, decodeSubtitleBuffer, listLocalEntries, listRemoteEntries } from './useSubtitles.js'
import { buildDecryptProxyUrl } from './useDecryptProxy.js'

/** `[mm:ss.xx]`, also `[mm:ss:xx]` and `[hh:mm:ss.xxx]` from sloppy taggers. */
const TIMESTAMP = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g

/** Tags that make `<track>.zh.lrc` a better pick than `<track>.foo.lrc`. */
const LANG_TAGS = [
  'zh', 'zh-cn', 'zh-hans', 'zh-sg', 'chs',
  'zh-tw', 'zh-hant', 'cht', 'en', 'ja', 'jp', 'ko',
]

/**
 * Parse LRC text into `[{ time, text }]`, sorted by time.
 *
 * Multiple timestamps on one line (`[00:12.00][00:48.00]副歌`) expand into one
 * entry each, and `[offset:±ms]` shifts everything, as the format specifies.
 */
export function parseLrc(text) {
  const clean = String(text || '')
    .replace(/^\uFEFF/, '')
    .replace(/\r\n?/g, '\n')

  const shift = /\[offset:\s*([+-]?\d+)\]/i.exec(clean)
  const offset = shift ? Number(shift[1]) / 1000 : 0

  const lines = []
  for (const raw of clean.split('\n')) {
    const line = raw.trim()
    if (!line) continue

    const stamps = []
    TIMESTAMP.lastIndex = 0
    let match = TIMESTAMP.exec(line)
    while (match) {
      const [, minutes, seconds, fraction = '0'] = match
      stamps.push(
        Number(minutes) * 60 + Number(seconds) + Number(fraction.padEnd(3, '0')) / 1000,
      )
      match = TIMESTAMP.exec(line)
    }
    if (!stamps.length) continue

    // What is left after stripping the timestamps is the line itself; an
    // instrumental break legitimately leaves it empty.
    const body = line.replace(TIMESTAMP, '').trim()
    for (const time of stamps) lines.push({ time: Math.max(0, time + offset), text: body })
  }

  return lines.sort((a, b) => a.time - b.time)
}

/** Index of the line playing at [time], or -1 before the first one. */
export function activeLineIndex(lines, time) {
  if (!lines.length) return -1

  let low = 0
  let high = lines.length - 1
  let found = -1

  while (low <= high) {
    const mid = (low + high) >> 1
    if (lines[mid].time <= time) {
      found = mid
      low = mid + 1
    } else {
      high = mid - 1
    }
  }

  return found
}

/** Candidate URLs for the lyric file, most reliable first. */
async function buildCandidateUrls(entry, mode) {
  if (mode === 'local') {
    return [`/ske-local/${encodeURI(entry.localPath)}`]
  }

  const encoded = entry.encName.endsWith('.ske')
  const info = await getFileInfo(entry.encPath)
  const urls = []

  if (encoded) {
    urls.push(buildDecryptProxyUrl(info.url, info.size))
    return urls
  }

  if (info.url) urls.push(info.url)
  const server = getServer()
  if (server) urls.push(`${server}/d${encodeURI(entry.encPath)}`)
  return urls
}

async function loadLyricText(entry, mode) {
  const urls = await buildCandidateUrls(entry, mode)
  let lastError = null

  for (const url of urls) {
    try {
      const res = await fetch(url)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      return decodeSubtitleBuffer(await res.arrayBuffer())
    } catch (err) {
      lastError = err
    }
  }

  throw lastError || new Error('lyrics unavailable')
}

export function useLyrics() {
  /** `lines` is the parsed timeline; `loading` drives the empty-state copy. */
  const lyric = reactive({ lines: [], loading: false })
  let token = 0

  /**
   * Scan the track's folder for its `.lrc` and parse it.
   *
   * @param {string} dirPath  encrypted folder path for remote files, or the
   *                          `/local/...` route path for local ones
   * @param {string} audioName  decrypted audio filename
   * @param {'alist'|'local'} mode
   */
  async function scan(dirPath, audioName, mode = 'alist') {
    const current = (token += 1)
    lyric.lines = []
    if (!audioName) {
      lyric.loading = false
      return
    }

    lyric.loading = true
    try {
      const nameKey = await getNameKeyFromSession()
      const entries = mode === 'local'
        ? await listLocalEntries(dirPath, nameKey)
        : await listRemoteEntries(dirPath, nameKey)
      if (current !== token) return

      const base = baseName(audioName).toLowerCase()
      const ranked = []
      for (const entry of entries) {
        const name = entry.decName.toLowerCase()
        if (!name.endsWith('.lrc')) continue
        const stem = name.slice(0, -4)
        if (stem === base) {
          ranked.push({ entry, rank: 0 })
        } else if (stem.startsWith(`${base}.`)) {
          const tag = stem.slice(base.length + 1)
          ranked.push({ entry, rank: LANG_TAGS.includes(tag) ? 1 : 2 })
        }
      }
      if (!ranked.length) return

      ranked.sort((a, b) => a.rank - b.rank || a.entry.decName.localeCompare(b.entry.decName))
      const text = await loadLyricText(ranked[0].entry, mode)
      if (current !== token) return
      lyric.lines = parseLrc(text)
    } catch {
      // A missing or unreadable .lrc is normal, not an error worth a dialog.
    } finally {
      if (current === token) lyric.loading = false
    }
  }

  return { lyric, scan }
}
