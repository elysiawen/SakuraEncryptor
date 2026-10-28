/**
 * Subtitle plumbing shared by the player window.
 *
 * The web viewer hands SRT/VTT to the browser's native cue renderer and ASS to
 * libass; a frameless Electron window has neither, so both formats are parsed
 * into a flat cue list here and drawn by a plain overlay. That keeps the look
 * identical across SRT/VTT/ASS and costs no extra dependency.
 */

export const SUBTITLE_EXTS = ['srt', 'vtt', 'ass', 'ssa']

const LANG_LABELS = {
  zh: '中文',
  chs: '简体中文',
  cht: '繁体中文',
  chi: '中文',
  zho: '中文',
  cn: '中文',
  en: 'English',
  eng: 'English',
  ja: '日本語',
  jpn: '日本語',
  jp: '日本語',
  ko: '한국어',
  kor: '한국어',
  fr: 'Français',
  de: 'Deutsch',
  es: 'Español',
  ru: 'Русский',
}

/** `show.s01e01.mkv` → `show.s01e01` */
export function baseName(name = '') {
  const file = String(name).split(/[\\/]/).pop() || ''
  const dot = file.lastIndexOf('.')
  return (dot > 0 ? file.slice(0, dot) : file).toLowerCase()
}

export function extensionOf(name = '') {
  const file = String(name).split(/[\\/]/).pop() || ''
  const dot = file.lastIndexOf('.')
  return dot > 0 ? file.slice(dot + 1).toLowerCase() : ''
}

export function isSubtitleName(name = '') {
  return SUBTITLE_EXTS.includes(extensionOf(name))
}

/**
 * Language suffix of a subtitle next to its video: `ep01.zh.srt` → `zh`.
 * Returns `null` when the names are unrelated, `''` for an exact match.
 */
export function langTagOf(videoBase, subBase) {
  const v = String(videoBase).toLowerCase()
  const s = String(subBase).toLowerCase()
  if (s === v) return ''
  if (s.length > v.length && s.startsWith(v)) {
    const sep = s[v.length]
    if (sep === '.' || sep === '-' || sep === '_' || sep === ' ') return subBase.slice(v.length + 1)
  }
  return null
}

export function trackLabel(tag, ext) {
  const suffix = String(ext || '').toUpperCase()
  if (!tag) return `默认 · ${suffix}`
  const label = LANG_LABELS[tag.toLowerCase()] || tag
  return `${label} · ${suffix}`
}

function parseTimestamp(value) {
  const match = /^(?:(\d+):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})$/.exec(String(value).trim())
  if (!match) return null
  const [, h = '0', m, s, ms] = match
  return Number(h) * 3600 + Number(m) * 60 + Number(s) + Number(ms.padEnd(3, '0')) / 1000
}

function parseAssTimestamp(value) {
  const match = /^(\d+):(\d{1,2}):(\d{1,2})[.:](\d{1,2})$/.exec(String(value).trim())
  if (!match) return null
  const [, h, m, s, cs] = match
  return Number(h) * 3600 + Number(m) * 60 + Number(s) + Number(cs) / 100
}

/** Drop ASS override blocks and line-break escapes. */
function stripAssText(text) {
  return String(text)
    .replace(/\{[^}]*\}/g, '')
    .replace(/\\[Nn]/g, '\n')
    .replace(/\\h/g, ' ')
    .trim()
}

function parseSrtCues(text) {
  const clean = String(text).replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n')
  const cues = []

  for (const block of clean.split(/\n{2,}/)) {
    const lines = block.split('\n').filter((line) => line.trim() !== '')
    if (!lines.length) continue

    const arrow = lines.findIndex((line) => line.includes('-->'))
    if (arrow < 0) continue

    const [from, rest] = lines[arrow].split('-->')
    const start = parseTimestamp(from)
    const end = parseTimestamp((rest || '').trim().split(/\s+/)[0] || '')
    if (start == null || end == null || end <= start) continue

    // Some encoders leave inline tags (<i>, <font>) behind; the overlay is
    // plain text, so they would show up literally.
    const body = lines
      .slice(arrow + 1)
      .join('\n')
      .replace(/<[^>]+>/g, '')
      .trim()
    if (!body) continue

    cues.push({ start, end, text: body })
  }

  return cues.sort((a, b) => a.start - b.start)
}

function parseAssCues(text) {
  const lines = String(text).replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n').split('\n')
  const cues = []
  let section = ''
  let fields = null

  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed) continue

    if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
      section = trimmed.toLowerCase()
      fields = null
      continue
    }
    if (section !== '[events]') continue

    if (/^format\s*:/i.test(trimmed)) {
      fields = trimmed
        .slice(trimmed.indexOf(':') + 1)
        .split(',')
        .map((field) => field.trim().toLowerCase())
      continue
    }
    if (!fields || !/^dialogue\s*:/i.test(trimmed)) continue

    // `Text` is the last field and may itself contain commas, so it is rejoined.
    const body = trimmed.slice(trimmed.indexOf(':') + 1)
    const parts = body.split(',')
    const textIndex = fields.indexOf('text')
    const values =
      textIndex >= 0 ? [...parts.slice(0, textIndex), parts.slice(textIndex).join(',')] : parts
    const pick = (key) => {
      const index = fields.indexOf(key)
      return index >= 0 ? (values[index] || '').trim() : ''
    }

    const start = parseAssTimestamp(pick('start'))
    const end = parseAssTimestamp(pick('end'))
    const content = stripAssText(pick('text'))
    if (start == null || end == null || end <= start || !content) continue

    cues.push({ start, end, text: content })
  }

  return cues.sort((a, b) => a.start - b.start)
}

/** Parse subtitle text into `[{ start, end, text }]`, sorted by start time. */
export function parseCues(text, ext) {
  const kind = String(ext || '').toLowerCase()
  if (kind === 'ass' || kind === 'ssa') return parseAssCues(text)
  return parseSrtCues(text)
}

/**
 * Cue visible at `time`. Binary search for the last started cue, then walk back
 * a few entries so overlapping cues still find the one that covers `time`.
 */
export function cueAt(cues, time) {
  if (!cues.length) return null

  let low = 0
  let high = cues.length - 1
  let found = -1

  while (low <= high) {
    const mid = (low + high) >> 1
    if (cues[mid].start <= time) {
      found = mid
      low = mid + 1
    } else {
      high = mid - 1
    }
  }

  for (let i = found; i >= 0 && i > found - 6; i -= 1) {
    if (time < cues[i].end) return cues[i]
  }
  return null
}
