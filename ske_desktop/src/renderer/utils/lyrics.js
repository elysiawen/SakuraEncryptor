/**
 * LRC parsing for the music window.
 *
 * A sidecar `.lrc` is the only lyric source: the app has no lyrics service, and
 * the file sits next to the track the same way a subtitle sits next to a video.
 * Only what the overlay needs is parsed — a time-sorted list of lines.
 */

/** `[mm:ss.xx]`, also `[mm:ss:xx]` and `[hh:mm:ss.xxx]` from sloppy taggers. */
const TIMESTAMP = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g

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

  // `[offset:+200]` is milliseconds and applies to every line.
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
