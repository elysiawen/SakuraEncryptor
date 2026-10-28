/**
 * Minimal ID3v2 cover reader.
 *
 * Audio artwork is the one thing the `.ske` container has no preview for, so the
 * cover has to come out of the file itself. Only the leading 256 KiB is fetched
 * (the loopback stream supports Range) and only the APIC/PIC frame is decoded —
 * a whole tag library is not worth it for one picture.
 */
const PREFIX_BYTES = 256 * 1024

function ascii(bytes, start, length) {
  let out = ''
  for (let i = 0; i < length; i += 1) out += String.fromCharCode(bytes[start + i])
  return out
}

/** ID3 sizes are stored 7 bits per byte so they never contain 0xFF. */
function syncsafe(view, offset, length) {
  let value = 0
  for (let i = 0; i < length; i += 1) value = (value << 7) | (view.getUint8(offset + i) & 0x7f)
  return value
}

/** Resolve a `blob:` URL for the embedded cover, or '' when there is none. */
export async function readCover(url) {
  try {
    const res = await fetch(url, { headers: { Range: `bytes=0-${PREFIX_BYTES - 1}` } })
    if (!res.ok && res.status !== 206) return ''

    const bytes = new Uint8Array(await res.arrayBuffer())
    return parseId3Cover(bytes)
  } catch {
    return ''
  }
}

function parseId3Cover(bytes) {
  if (bytes.length < 10 || ascii(bytes, 0, 3) !== 'ID3') return ''

  const major = bytes[3]
  if (major < 2 || major > 4) return ''
  // Unsynchronised tags relocate bytes; skipping is safer than misreading them.
  if (bytes[5] & 0x80) return ''

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const end = Math.min(bytes.length, 10 + syncsafe(view, 6, 4))
  const frameHeader = major === 2 ? 6 : 10
  const frameIdLength = major === 2 ? 3 : 4

  let offset = 10
  while (offset + frameHeader <= end) {
    const id = ascii(bytes, offset, frameIdLength)
    if (!/^[A-Z0-9]{3,4}$/.test(id)) return ''

    const frameSize =
      major === 2
        ? (bytes[offset + 3] << 16) | (bytes[offset + 4] << 8) | bytes[offset + 5]
        : major === 4
          ? syncsafe(view, offset + 4, 4)
          : view.getUint32(offset + 4)

    const bodyStart = offset + frameHeader
    const bodyEnd = bodyStart + frameSize
    if (frameSize <= 0 || bodyEnd > end) return ''

    if (id === 'APIC' || id === 'PIC') {
      const cover = parsePicture(bytes, bodyStart, bodyEnd, major)
      if (cover) return cover
    }

    offset = bodyEnd
  }

  return ''
}

function parsePicture(bytes, start, end, major) {
  if (start >= end) return ''

  const encoding = bytes[start]
  let cursor = start + 1
  let mime = 'image/jpeg'

  if (major === 2) {
    mime = ascii(bytes, cursor, 3).toLowerCase() === 'png' ? 'image/png' : 'image/jpeg'
    cursor += 3
  } else {
    const mimeStart = cursor
    while (cursor < end && bytes[cursor] !== 0) cursor += 1
    if (cursor >= end) return ''
    mime = ascii(bytes, mimeStart, cursor - mimeStart) || mime
    cursor += 1
  }

  cursor += 1 // picture type
  if (cursor >= end) return ''

  // The description is null-terminated: two bytes per character in UTF-16.
  if (encoding === 1 || encoding === 2) {
    while (cursor + 1 < end && !(bytes[cursor] === 0 && bytes[cursor + 1] === 0)) cursor += 2
    cursor += 2
  } else {
    while (cursor < end && bytes[cursor] !== 0) cursor += 1
    cursor += 1
  }

  if (cursor >= end) return ''
  return URL.createObjectURL(new Blob([bytes.subarray(cursor, end)], { type: mime }))
}
