/**
 * Embedded metadata reader for the music player: cover art, title, artist and
 * album, as stored inside the audio file itself.
 *
 * Ported from the Android extractor (`core/player/AudioTags.kt`) and reduced to
 * what the desktop needs. The four places these live in the wild are all handled:
 *
 *   - ID3v2 `APIC` / `PIC` and `TIT2` / `TPE1` / `TALB` — mp3, and the stray tag
 *     some taggers prepend to FLAC
 *   - FLAC `PICTURE` and `VORBIS_COMMENT`, including a base64
 *     `METADATA_BLOCK_PICTURE` inside the comments
 *   - the Ogg / Opus comment header
 *   - MP4 / M4A `moov.udta.meta.ilst` (`covr`, `©nam`, `©ART`, `©alb`), with
 *     `moov` anywhere in the file
 *
 * Reads are ranged and random access, not a fixed "first 256 KiB" window: the
 * loopback media endpoint decrypts on demand, so walking block headers costs a
 * few kilobytes of blocks while the picture itself is fetched at its real size.
 * That is what makes MP4 (metadata at the end of the file) and FLAC (picture
 * after the comments) work at all.
 */

/** Read granularity for headers; bodies larger than this are fetched directly. */
const CHUNK_BYTES = 64 * 1024
/** Piece size for large reads: keeps each response well under the server's cap. */
const BIG_READ_BYTES = 512 * 1024
/** Cover art ceiling, mirroring the Android extractor. */
const MAX_ARTWORK_BYTES = 8 * 1024 * 1024
/** Comment blocks can legitimately carry a base64 picture inside them. */
const MAX_COMMENT_BYTES = 8 * 1024 * 1024
/** A text frame is a title, not an essay. */
const MAX_TEXT_BYTES = 64 * 1024
/** A server that ignores Range must not make us buffer a whole album track. */
const MAX_UNRANGED_RESPONSE = 32 * 1024 * 1024

// ── Byte helpers ─────────────────────────────────────────────────────

function ascii(bytes, start, length) {
  let out = ''
  for (let i = 0; i < length; i += 1) out += String.fromCharCode(bytes[start + i])
  return out
}

/** ID3 sizes are stored 7 bits per byte so they never contain 0xFF. */
function syncsafe(bytes, offset) {
  if (offset + 4 > bytes.length) return 0
  return (
    ((bytes[offset] & 0x7f) << 21) |
    ((bytes[offset + 1] & 0x7f) << 14) |
    ((bytes[offset + 2] & 0x7f) << 7) |
    (bytes[offset + 3] & 0x7f)
  )
}

function be32(bytes, offset) {
  if (offset + 4 > bytes.length) return 0
  return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0
}

function be64(bytes, offset) {
  if (offset + 8 > bytes.length) return 0
  return be32(bytes, offset) * 0x100000000 + be32(bytes, offset + 4)
}

function le32(bytes, offset) {
  if (offset + 4 > bytes.length) return 0
  return (bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) | (bytes[offset + 3] << 24)) >>> 0
}

function decode(bytes, label) {
  try {
    return new TextDecoder(label).decode(bytes)
  } catch {
    return ''
  }
}

/** Tags are NUL-terminated and padded; both are noise to the UI. */
function cleanText(value) {
  return String(value || '')
    .replace(/\0+$/g, '')
    .trim()
}

// ── Ranged reader ────────────────────────────────────────────────────

/**
 * Random-access reader over the stream URL.
 *
 * Small reads come from 64 KiB chunks, so walking frame headers costs one
 * request instead of one per field; big reads are fetched directly in pieces.
 * `size` is learned from the first `Content-Range` and is what lets the parsers
 * know where a tag or a box ends.
 */
function createReader(url) {
  const chunks = new Map()
  let size = 0
  /** Set when a server ignored Range: the body is kept and sliced locally. */
  let whole = null

  function sliceOf(bytes, start, end) {
    if (start >= bytes.length) return null
    const piece = bytes.subarray(start, Math.min(bytes.length, end + 1))
    return piece.length > 0 ? piece : null
  }

  async function fetchRange(start, end) {
    if (whole) return sliceOf(whole, start, end)

    const res = await fetch(url, { headers: { Range: `bytes=${start}-${end}` } })
    if (res.status !== 206 && !res.ok) return null

    if (res.status === 206) {
      const range = res.headers.get('content-range') || ''
      const total = Number(range.split('/')[1])
      if (Number.isFinite(total) && total > 0) size = total
      const bytes = new Uint8Array(await res.arrayBuffer())
      return bytes.length > 0 ? bytes : null
    }

    // Range ignored: only acceptable for something small enough to hold, and
    // then it is held once — re-downloading the track per header would be
    // worse than the memory.
    const body = new Uint8Array(await res.arrayBuffer())
    if (body.length > MAX_UNRANGED_RESPONSE) return null
    whole = body
    size = body.length
    return sliceOf(whole, start, end)
  }

  function chunk(index) {
    if (chunks.has(index)) return chunks.get(index)
    const task = fetchRange(index * CHUNK_BYTES, (index + 1) * CHUNK_BYTES - 1)
    chunks.set(index, task)
    return task
  }

  return {
    get size() {
      return size
    },

    /**
     * Exactly [length] bytes at [position], or null when the stream ends early.
     * Every parser treats null as "stop", which is why nothing needs bounds
     * checks against a size that may still be unknown.
     */
    async exact(position, length) {
      if (length <= 0 || position < 0) return null
      if (length > CHUNK_BYTES) {
        const out = new Uint8Array(length)
        for (let copied = 0; copied < length; ) {
          const piece = Math.min(BIG_READ_BYTES, length - copied)
          const bytes = await fetchRange(position + copied, position + copied + piece - 1)
          if (!bytes || bytes.length !== piece) return null
          out.set(bytes, copied)
          copied += piece
        }
        return out
      }

      const first = Math.floor(position / CHUNK_BYTES)
      const last = Math.floor((position + length - 1) / CHUNK_BYTES)
      if (first !== last) return fetchRange(position, position + length - 1)

      const bytes = await chunk(first)
      if (!bytes) return null
      const offset = position - first * CHUNK_BYTES
      return offset + length <= bytes.length ? bytes.subarray(offset, offset + length) : null
    },
  }
}

/** Read a block, refusing anything bigger than [limit] instead of truncating it. */
function readCapped(reader, position, length, limit) {
  if (length <= 0 || length > limit) return null
  return reader.exact(position, length)
}

// ── Public entry point ───────────────────────────────────────────────

/**
 * Read the embedded metadata of [url].
 *
 * @returns {Promise<{ cover: string, title: string, artist: string, album: string }>}
 *   `cover` is a `blob:` URL (or ''), the rest are plain strings (or '').
 */
export async function readAudioTags(url) {
  const empty = { cover: '', title: '', artist: '', album: '' }
  try {
    const tags = await extractTags(url)
    if (!tags) return empty

    const picture = tags.picture
    return {
      cover:
        picture && picture.length >= 4
          ? URL.createObjectURL(new Blob([picture], { type: sniffMime(picture) }))
          : '',
      title: cleanText(tags.title),
      artist: cleanText(tags.artist),
      album: cleanText(tags.album),
    }
  } catch {
    return empty
  }
}

/** Cover only — kept for callers that have no use for the text fields. */
export async function readCover(url) {
  return (await readAudioTags(url)).cover
}

async function extractTags(url) {
  const reader = createReader(url)
  const head = await reader.exact(0, 12)
  if (!head) return null

  // An ID3v2 tag in front of the real container is not valid FLAC, but plenty
  // of taggers write one anyway — the real metadata lives in the container
  // behind it, so look there first and fill the gaps from the tag.
  if (isId3v2(head)) {
    const behind = await id3v2TotalSize(reader)
    if (behind != null) {
      const probe = await reader.exact(behind, 12)
      if (probe) {
        const found = isFlac(probe)
          ? await readFlacTags(reader, behind)
          : isOgg(probe)
            ? await readOggTags(reader, behind)
            : null
        if (found && hasContent(found)) {
          const tagged = await readId3Tags(reader)
          return tagged ? mergeTags(found, tagged) : found
        }
      }
    }
    return readId3Tags(reader)
  }

  if (isFlac(head)) return readFlacTags(reader, 0)
  if (isOgg(head)) return readOggTags(reader, 0)
  if (isMp4(head)) return readMp4Tags(reader)
  return null
}

function emptyTags() {
  return { picture: null, title: '', artist: '', album: '' }
}

function hasContent(tags) {
  return !!(tags.picture || tags.title || tags.artist || tags.album)
}

/**
 * Fills [target] with whatever [source] has and it does not, then returns
 * [target]: the parser that already collected fields (FLAC picture first,
 * comment second) keeps them, and a caller can also use it as a fold.
 */
function mergeTags(target, source) {
  target.picture ||= source.picture
  target.title ||= source.title
  target.artist ||= source.artist
  target.album ||= source.album
  return target
}

function sniffMime(bytes) {
  if (bytes[0] === 0xff && bytes[1] === 0xd8) return 'image/jpeg'
  if (bytes[0] === 0x89 && ascii(bytes, 1, 3) === 'PNG') return 'image/png'
  if (ascii(bytes, 0, 3) === 'GIF') return 'image/gif'
  if (ascii(bytes, 0, 4) === 'RIFF' && ascii(bytes, 8, 4) === 'WEBP') return 'image/webp'
  if (bytes[0] === 0x42 && bytes[1] === 0x4d) return 'image/bmp'
  return 'image/jpeg'
}

// ── ID3v2 ────────────────────────────────────────────────────────────

function isId3v2(bytes) {
  return bytes.length >= 3 && ascii(bytes, 0, 3) === 'ID3'
}

/** Bytes occupied by the ID3v2 tag at the head of the stream, or null. */
async function id3v2TotalSize(reader) {
  const header = await reader.exact(0, 10)
  if (!header) return null
  const tagSize = syncsafe(header, 6)
  if (tagSize <= 0) return null
  // A v2.4 footer, when flagged, sits after the tag.
  const footer = header[5] & 0x10 ? 10 : 0
  return 10 + tagSize + footer
}

async function readId3Tags(reader) {
  const header = await reader.exact(0, 10)
  if (!header) return null

  const major = header[3]
  if (major < 2 || major > 4) return null
  const tagSize = syncsafe(header, 6)
  if (tagSize <= 0) return null

  let offset = 10
  if (header[5] & 0x40) {
    const extended = await reader.exact(offset, 4)
    if (!extended) return null
    // ID3v2.4 counts the size field itself, v2.3 does not.
    offset += major >= 4 ? syncsafe(extended, 0) : 4 + be32(extended, 0)
  }

  const end = Math.min(reader.size || Infinity, 10 + tagSize)
  const headerSize = major === 2 ? 6 : 10
  const idLength = major === 2 ? 3 : 4
  const tags = emptyTags()

  while (offset + headerSize <= end) {
    const frame = await reader.exact(offset, headerSize)
    if (!frame) break

    const id = ascii(frame, 0, idLength)
    if (id.charCodeAt(0) === 0) break

    const frameSize =
      major === 2
        ? (frame[3] << 16) | (frame[4] << 8) | frame[5]
        : major >= 4
          ? syncsafe(frame, 4)
          : be32(frame, 4)

    const bodyStart = offset + headerSize
    if (frameSize <= 0 || bodyStart + frameSize > end) break

    // Bodies of unwanted frames are skipped, not read: walking large art is
    // what keeps this cheap.
    if (id === 'APIC' || id === 'PIC') {
      const body = await readCapped(reader, bodyStart, frameSize, MAX_ARTWORK_BYTES)
      const picture = body && parseApic(body, major === 2)
      if (picture && !tags.picture) tags.picture = picture
    } else {
      const field = ID3_TEXT_FRAMES[id]
      if (field) {
        const body = await readCapped(reader, bodyStart, frameSize, MAX_TEXT_BYTES)
        const text = body && decodeId3Text(body)
        if (text && !tags[field]) tags[field] = text
      }
    }

    offset = bodyStart + frameSize
  }

  return tags
}

/** v2.2 uses three-character ids, v2.3/2.4 four-character ones. */
const ID3_TEXT_FRAMES = {
  TIT2: 'title',
  TT2: 'title',
  TPE1: 'artist',
  TP1: 'artist',
  TALB: 'album',
  TAL: 'album',
}

/**
 * ID3 text frames start with an encoding byte.
 *
 * Encoding 0 is nominally Latin-1, but Chinese taggers wrote GBK into it for
 * years, so the bytes are tried as GBK when they are not plain ASCII and the
 * result is valid — otherwise accented Latin text would be mangled instead.
 */
function decodeId3Text(bytes) {
  const encoding = bytes[0]
  const body = bytes.subarray(1)
  if (!body.length) return ''

  let text = ''
  if (encoding === 1) {
    text = decodeUtf16(body)
  } else if (encoding === 2) {
    text = decode(body, 'utf-16be')
  } else if (encoding === 3) {
    text = decode(body, 'utf-8')
  } else {
    const latin = decode(body, 'iso-8859-1')
    text = /[\u0080-\u00ff]/.test(latin) ? decodeGbk(body) || latin : latin
  }

  // Values are NUL-separated lists (e.g. several artists).
  return cleanText(text.split('\0').filter(Boolean).join(' / '))
}

function decodeUtf16(bytes) {
  if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) {
    return decode(bytes.subarray(2), 'utf-16le')
  }
  if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) {
    return decode(bytes.subarray(2), 'utf-16be')
  }
  return decode(bytes, 'utf-16le')
}

function decodeGbk(bytes) {
  try {
    const text = new TextDecoder('gbk').decode(bytes)
    return text.includes('\ufffd') ? '' : text
  } catch {
    return ''
  }
}

/**
 * `APIC` layout: encoding, MIME (NUL-terminated), picture type, description
 * (NUL-terminated), image bytes. ID3v2.2's `PIC` swaps the MIME string for a
 * fixed three-character format code.
 */
function parseApic(body, version2) {
  if (body.length < 4) return null

  const encoding = body[0]
  let position = 1
  if (version2) {
    position += 3
  } else {
    while (position < body.length && body[position] !== 0) position += 1
    position += 1
  }
  if (position >= body.length) return null

  position += 1 // picture type
  if (position >= body.length) return null

  // Description: two bytes per character for UTF-16 encodings, one otherwise.
  const wide = encoding === 1 || encoding === 2
  while (position < body.length) {
    if (wide) {
      const terminated =
        position + 1 < body.length && body[position] === 0 && body[position + 1] === 0
      position += 2
      if (terminated) break
    } else {
      const terminated = body[position] === 0
      position += 1
      if (terminated) break
    }
  }
  if (position >= body.length) return null

  return body.subarray(position)
}

// ── FLAC ─────────────────────────────────────────────────────────────

function isFlac(bytes) {
  return bytes.length >= 4 && ascii(bytes, 0, 4) === 'fLaC'
}

async function readFlacTags(reader, start) {
  const tags = emptyTags()
  let offset = start + 4

  while (true) {
    const header = await reader.exact(offset, 4)
    if (!header) break

    const last = header[0] & 0x80
    const type = header[0] & 0x7f
    const length = (header[1] << 16) | (header[2] << 8) | header[3]
    const bodyStart = offset + 4

    if (type === 6) {
      const body = await readCapped(reader, bodyStart, length, MAX_ARTWORK_BYTES)
      const picture = body && parseFlacPicture(body, 0, body.length)
      if (picture && !tags.picture) tags.picture = picture
    } else if (type === 4) {
      const body = await readCapped(reader, bodyStart, length, MAX_COMMENT_BYTES)
      if (body) mergeTags(tags, parseVorbisComment(body, 0, body.length))
    }

    // Jumping straight to the next header is the point: a huge PICTURE never
    // blocks the comment behind it, and a huge comment never blocks the picture.
    offset = bodyStart + length
    if (last) break
  }

  return tags
}

/**
 * A `PICTURE` block: type, MIME, description, four big-endian ints (width,
 * height, depth, colours), then the length and bytes of the image.
 */
function parseFlacPicture(bytes, start, end) {
  let position = start + 4
  if (position + 4 > end) return null

  position += 4 + be32(bytes, position)
  if (position + 4 > end) return null

  position += 4 + be32(bytes, position)
  if (position + 16 > end) return null

  position += 16
  if (position + 4 > end) return null

  const length = be32(bytes, position)
  position += 4
  if (length <= 0 || length > MAX_ARTWORK_BYTES || position + length > end) return null

  return bytes.subarray(position, position + length)
}

// ── Vorbis comments (FLAC, Ogg, Opus) ────────────────────────────────

/** Offset of the comment count, after the vendor string. */
function vorbisCommentStart(bytes, start, end) {
  if (start + 4 > end) return null
  const offset = start + 4 + le32(bytes, start)
  return offset + 4 > end ? null : offset
}

/**
 * `VORBIS_COMMENT` body: vendor string, count, then `KEY=value` entries.
 * `ARTIST` may repeat; the rest are first-wins so a stray duplicate cannot
 * overwrite a good value.
 */
function parseVorbisComment(bytes, start, end) {
  const tags = emptyTags()
  const countAt = vorbisCommentStart(bytes, start, end)
  if (countAt == null) return tags

  let offset = countAt
  const count = le32(bytes, offset)
  offset += 4
  const artists = []

  for (let i = 0; i < count; i += 1) {
    if (offset + 4 > end) break
    const length = le32(bytes, offset)
    offset += 4
    if (length <= 0 || offset + length > end) break

    const entry = decode(bytes.subarray(offset, offset + length), 'utf-8')
    offset += length

    const separator = entry.indexOf('=')
    if (separator <= 0) continue
    const key = entry.slice(0, separator).toUpperCase()
    const value = cleanText(entry.slice(separator + 1))
    if (!value) continue

    if (key === 'TITLE') tags.title ||= value
    else if (key === 'ALBUM') tags.album ||= value
    else if (key === 'ARTIST') artists.push(value)
    else if (key === 'METADATA_BLOCK_PICTURE' && !tags.picture) {
      const decoded = decodeBase64(value)
      const picture = decoded && parseFlacPicture(decoded, 0, decoded.length)
      if (picture) tags.picture = picture
    }
  }

  tags.artist = artists.join(' / ')
  return tags
}

function decodeBase64(value) {
  try {
    const binary = atob(value.replace(/\s+/g, ''))
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
    return bytes
  } catch {
    return null
  }
}

// ── Ogg / Vorbis / Opus ──────────────────────────────────────────────

function isOgg(bytes) {
  return bytes.length >= 4 && ascii(bytes, 0, 4) === 'OggS'
}

/**
 * Rebuilds Ogg packets and looks at the second one, which is the comment header
 * for both Vorbis and Opus. Reassembly matters: a comment header carrying a
 * base64 picture spills across pages.
 */
async function readOggTags(reader, start) {
  let offset = start
  let packetIndex = 0
  let pending = []
  let pendingBytes = 0

  while (offset + 27 <= (reader.size || Infinity)) {
    const page = await reader.exact(offset, 27)
    if (!page || !isOgg(page)) return null

    const segments = page[26]
    const table = await reader.exact(offset + 27, segments)
    if (!table) return null

    const bodyStart = offset + 27 + segments
    let bodyLength = 0
    for (let i = 0; i < segments; i += 1) bodyLength += table[i]

    const body = bodyLength > 0 ? await reader.exact(bodyStart, bodyLength) : new Uint8Array(0)
    if (!body) return null

    let cursor = 0
    for (let i = 0; i < segments; i += 1) {
      const length = table[i]
      pending.push(body.subarray(cursor, cursor + length))
      pendingBytes += length
      cursor += length

      if (pendingBytes > MAX_COMMENT_BYTES) return null

      // A segment shorter than 255 ends the packet.
      if (length < 255) {
        const packet = concat(pending, pendingBytes)
        pending = []
        pendingBytes = 0

        if (packetIndex === 1) return oggCommentTags(packet)
        packetIndex += 1
        // The comment header is always the second packet.
        if (packetIndex > 1) return null
      }
    }

    offset = bodyStart + bodyLength
  }

  return null
}

function concat(parts, total) {
  if (parts.length === 1) return parts[0]
  const out = new Uint8Array(total)
  let at = 0
  for (const part of parts) {
    out.set(part, at)
    at += part.length
  }
  return out
}

function oggCommentTags(packet) {
  let commentStart = null
  if (packet.length > 7 && packet[0] === 3 && ascii(packet, 1, 6) === 'vorbis') {
    commentStart = 7
  } else if (packet.length > 8 && ascii(packet, 0, 8) === 'OpusTags') {
    commentStart = 8
  }
  if (commentStart == null) return null

  return parseVorbisComment(packet, commentStart, packet.length)
}

// ── MP4 / M4A ────────────────────────────────────────────────────────

function isMp4(bytes) {
  return bytes.length >= 8 && ascii(bytes, 4, 4) === 'ftyp'
}

/** `©nam`, `©ART`, `©alb` — the © is byte 0xA9, not an ASCII character. */
const MP4_TEXT_ATOMS = {
  '\u00a9nam': 'title',
  '\u00a9ART': 'artist',
  '\u00a9alb': 'album',
}

/**
 * Finds the metadata in `moov.udta.meta.ilst`.
 *
 * `moov` sits at the end of the file unless it was written for streaming, so
 * the top level is walked box by box instead of assuming a position.
 */
async function readMp4Tags(reader) {
  const size = reader.size
  if (!size) return null

  const moov = await findBox(reader, 0, size, 'moov')
  if (!moov) return null
  const udta = await findBox(reader, moov.start, moov.end, 'udta')
  if (!udta) return null
  // `meta` is a full box, so its children start four bytes in.
  const meta = await findBox(reader, udta.start, udta.end, 'meta', 4)
  if (!meta) return null
  const ilst = await findBox(reader, meta.start, meta.end, 'ilst')
  if (!ilst) return null

  const tags = emptyTags()
  let offset = ilst.start

  while (offset + 8 <= ilst.end) {
    const header = await reader.exact(offset, 8)
    if (!header) break

    const boxSize = be32(header, 0)
    const boxType = ascii(header, 4, 4)
    if (boxSize < 8 || offset + boxSize > ilst.end) break

    const field = MP4_TEXT_ATOMS[boxType]
    if (boxType === 'covr' || field) {
      const data = await findBox(reader, offset + 8, offset + boxSize, 'data')
      if (data) {
        // A `data` box is type/flags, locale, then the payload.
        const payloadStart = data.start + 8
        const payloadLength = data.end - payloadStart

        if (boxType === 'covr') {
          const picture = await readCapped(reader, payloadStart, payloadLength, MAX_ARTWORK_BYTES)
          if (picture && !tags.picture) tags.picture = picture
        } else if (!tags[field]) {
          const payload = await readCapped(reader, payloadStart, payloadLength, MAX_TEXT_BYTES)
          if (payload) tags[field] = cleanText(decode(payload, 'utf-8'))
        }
      }
    }

    offset += boxSize
  }

  return tags
}

/** A box's payload range, or null when [type] is not among the children. */
async function findBox(reader, start, end, type, contentSkip = 0) {
  let offset = start

  while (offset + 8 <= end) {
    const header = await reader.exact(offset, 8)
    if (!header) return null

    let boxSize = be32(header, 0)
    const boxType = ascii(header, 4, 4)
    let contentStart = offset + 8

    if (boxSize === 1) {
      // 64-bit size follows the type.
      const extended = await reader.exact(offset + 8, 8)
      if (!extended) return null
      boxSize = be64(extended, 0)
      contentStart = offset + 16
    } else if (boxSize === 0) {
      // Runs to the end of the enclosing box.
      boxSize = end - offset
    }

    if (boxSize < contentStart - offset) return null
    const boxEnd = offset + boxSize
    if (boxEnd > end) return null

    if (boxType === type) return { start: Math.min(contentStart + contentSkip, boxEnd), end: boxEnd }
    offset = boxEnd
  }

  return null
}
