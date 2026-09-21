/**
 * useSubtitles — Discover, download and decode external subtitles for a video.
 *
 * Flow:
 *   1. List the video's folder (encrypted names are decrypted with the session key).
 *   2. Keep entries whose decrypted name is a subtitle sharing the video's basename
 *      (e.g. `movie.zh-CN.srt` for `movie.mkv`).
 *   3. Download the text — encrypted subtitles go through the Service Worker
 *      (`/ske-decrypt/`), plaintext ones are fetched directly (with an AList
 *      proxy fallback for CORS-restricted hosts).
 *   4. Decode the bytes (UTF-8 / GBK / UTF-16) and normalise to WebVTT, except
 *      for ASS/SSA which are handed to JASSUB untouched.
 */
import { ref } from 'vue'
import { listDir, getFileInfo, getServer } from './useAList.js'
import { getNameKeyFromSession, decryptName } from './useCrypto.js'
import { isSubtitle, getExt } from './useFileDetection.js'
import { buildDecryptProxyUrl } from './useDecryptProxy.js'

const LANG_LABELS = {
    'zh': '中文', 'chi': '中文',
    'chs': '简体中文', 'sc': '简体中文', 'zh-cn': '简体中文', 'zh-hans': '简体中文', 'zh_cn': '简体中文',
    'cht': '繁體中文', 'tc': '繁體中文', 'zh-tw': '繁體中文', 'zh-hant': '繁體中文', 'zh_tw': '繁體中文',
    'en': 'English', 'eng': 'English',
    'ja': '日本語', 'jp': '日本語', 'jpn': '日本語',
    'ko': '한국어', 'kor': '한국어',
    'fr': 'Français', 'fre': 'Français',
    'de': 'Deutsch', 'ger': 'Deutsch',
    'es': 'Español', 'spa': 'Español',
    'ru': 'Русский', 'rus': 'Русский',
    'bilingual': '双语', 'bi': '双语',
}

/** Strip the last extension: "a.b.srt" -> "a.b". */
function baseName(name) {
    const i = name.lastIndexOf('.')
    return i > 0 ? name.slice(0, i) : name
}

/**
 * Return the language tag when *subBase* belongs to the video, `''` for an exact
 * basename match, or `null` when it does not belong to this video at all.
 */
function langTagOf(videoBase, subBase) {
    const v = videoBase.toLowerCase()
    const s = subBase.toLowerCase()
    if (s === v) return ''
    if (s.length > v.length && s.startsWith(v)) {
        const sep = s[v.length]
        if (sep === '.' || sep === '-' || sep === '_' || sep === ' ') {
            return subBase.slice(v.length + 1)
        }
    }
    return null
}

function langLabel(tag) {
    if (!tag) return ''
    return LANG_LABELS[tag.toLowerCase()] || tag.toUpperCase()
}

function buildLabel(tag, ext) {
    const type = (ext || 'sub').toUpperCase()
    const lang = langLabel(tag)
    return lang ? `${lang} · ${type}` : type
}

function joinPath(dir, name) {
    if (!dir) return '/' + name
    return (dir.endsWith('/') ? dir : dir + '/') + name
}

/** Decode raw subtitle bytes, guessing the encoding from BOM + validity. */
export function decodeSubtitleBuffer(buffer) {
    const bytes = new Uint8Array(buffer)
    if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
        return new TextDecoder('utf-8').decode(bytes.subarray(3))
    }
    if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) {
        return new TextDecoder('utf-16le').decode(bytes.subarray(2))
    }
    if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) {
        return new TextDecoder('utf-16be').decode(bytes.subarray(2))
    }
    try {
        return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    } catch {
        // Not valid UTF-8 — fall through to the common CJK encodings.
    }
    for (const enc of ['gbk', 'big5']) {
        try {
            return new TextDecoder(enc).decode(bytes)
        } catch {
            // Unsupported label in this browser — try the next one.
        }
    }
    return new TextDecoder('utf-8').decode(bytes)
}

/** Convert SubRip text to WebVTT. */
export function srtToVtt(srt) {
    const clean = srt.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n')
    const stamped = clean.replace(
        /(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})/g,
        (_m, h, mi, s, ms) => `${h.padStart(2, '0')}:${mi}:${s}.${ms.padEnd(3, '0')}`,
    )
    return 'WEBVTT\n\n' + stamped
}

function ensureVtt(text) {
    const clean = text.replace(/^\uFEFF/, '')
    return /^\s*WEBVTT/.test(clean) ? clean : 'WEBVTT\n\n' + clean
}

/**
 * Candidate URLs for a subtitle, most reliable first.
 * Encrypted files always go through the SW proxy (same-origin, no CORS).
 * Plaintext files try the direct link, then AList's own /d/ proxy.
 */
async function buildCandidateUrls(track) {
    const encoded = track.encName.endsWith('.ske')
    const info = await getFileInfo(track.encPath)
    const urls = []

    if (encoded) {
        urls.push(buildDecryptProxyUrl(info.url, info.size))
        return urls
    }

    if (info.url) urls.push(info.url)
    const server = getServer()
    if (server) urls.push(`${server}/d${encodeURI(track.encPath)}`)
    return urls
}

/** Download + decode a subtitle's text. Throws if every candidate fails. */
export async function loadSubtitleText(track) {
    const urls = await buildCandidateUrls(track)
    let lastError = null

    for (const url of urls) {
        try {
            const res = await fetch(url)
            if (!res.ok) throw new Error(`HTTP ${res.status}`)
            const text = decodeSubtitleBuffer(await res.arrayBuffer())
            if (!text || !text.trim()) throw new Error('字幕内容为空')
            return text
        } catch (err) {
            lastError = err
        }
    }
    throw lastError || new Error('字幕加载失败')
}

/** Build the payload consumed by VideoPlayer. */
export async function buildSubtitlePayload(track) {
    const text = await loadSubtitleText(track)
    const ext = track.ext
    if (ext === 'ass' || ext === 'ssa') {
        return { kind: 'ass', content: text, name: track.label }
    }
    return { kind: 'vtt', content: ext === 'vtt' ? ensureVtt(text) : srtToVtt(text), name: track.label }
}

export function useSubtitles() {
    const tracks = ref([])
    const loading = ref(false)

    /**
     * Scan *dirPath* for subtitles matching the video.
     * @param {string} dirPath  encrypted folder path (e.g. "/a/b")
     * @param {string} decVideoName  decrypted video filename (e.g. "movie.mkv")
     */
    async function scan(dirPath, decVideoName) {
        tracks.value = []
        if (!decVideoName) return

        loading.value = true
        try {
            const nameKey = await getNameKeyFromSession()
            const data = await listDir(dirPath)
            const files = (data.content || []).filter(entry => !entry.is_dir)

            const mapped = await Promise.all(
                files.map(async entry => {
                    let decName = entry.name
                    if (nameKey) {
                        let nameToDecrypt = entry.name
                        if (nameToDecrypt.endsWith('.ske')) nameToDecrypt = nameToDecrypt.slice(0, -4)
                        const result = await decryptName(nameToDecrypt, nameKey)
                        if (result) decName = result
                    }
                    return { encName: entry.name, decName, size: entry.size }
                }),
            )

            const videoBase = baseName(decVideoName)
            const matched = []
            for (const item of mapped) {
                if (!isSubtitle(item.decName)) continue
                const tag = langTagOf(videoBase, baseName(item.decName))
                if (tag === null) continue
                matched.push({ ...item, tag, ext: getExt(item.decName) })
            }

            // Exact basename match first, then alphabetically by language tag.
            matched.sort((a, b) => {
                if (a.tag === '' && b.tag !== '') return -1
                if (b.tag === '' && a.tag !== '') return 1
                return a.tag.localeCompare(b.tag, 'zh-CN')
            })

            tracks.value = matched.map(item => ({
                id: item.encName,
                encName: item.encName,
                encPath: joinPath(dirPath, item.encName),
                decName: item.decName,
                ext: item.ext,
                language: item.tag,
                label: buildLabel(item.tag, item.ext),
            }))
        } catch {
            tracks.value = []
        } finally {
            loading.value = false
        }
    }

    return { tracks, loading, scan }
}
