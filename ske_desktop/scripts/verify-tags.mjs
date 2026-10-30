/**
 * Offline check for the embedded-metadata reader.
 *
 * Usage:
 *   node scripts/verify-tags.mjs
 *
 * Builds a minimal mp3 / flac / ogg / m4a in memory, serves each one over HTTP
 * twice — once honouring Range, once ignoring it — and compares what
 * `readAudioTags` reports against what was written into the tags. Nothing here
 * needs a real track, so it can run in a fresh checkout.
 */
import http from 'node:http'
import process from 'node:process'
import { describeTrack, ensureTags, trackName } from '../src/renderer/state/track-tags.js'
import { readAudioTags } from '../src/renderer/utils/audio-tags.js'

let checks = 0
let failures = 0

function check(label, actual, expected) {
  checks += 1
  if (actual !== expected) {
    failures += 1
    console.error(`FAIL ${label}: got ${JSON.stringify(actual)}, expected ${JSON.stringify(expected)}`)
  }
}

// ── Byte builders ────────────────────────────────────────────────────

const cat = (...parts) => Buffer.concat(parts)

function be32(value) {
  const out = Buffer.alloc(4)
  out.writeUInt32BE(value)
  return out
}

function le32(value) {
  const out = Buffer.alloc(4)
  out.writeUInt32LE(value)
  return out
}

function syncsafe(value) {
  return Buffer.from([(value >> 21) & 0x7f, (value >> 14) & 0x7f, (value >> 7) & 0x7f, value & 0x7f])
}

function nulText(text, encoding = 'utf8') {
  return cat(Buffer.from(text, encoding), Buffer.alloc(1))
}

/** 1 KiB of JPEG-ish bytes: enough to exercise the payload plumbing. */
function fakeJpeg() {
  const body = Buffer.alloc(1024, 0x5a)
  body[0] = 0xff
  body[1] = 0xd8
  body[body.length - 2] = 0xff
  body[body.length - 1] = 0xd9
  return body
}

// ── ID3v2.3 ──────────────────────────────────────────────────────────

function id3Frame(id, body) {
  const head = Buffer.alloc(10)
  head.write(id, 0, 'latin1')
  head.writeUInt32BE(body.length, 4)
  return cat(head, body)
}

function id3Apic(mime, picture) {
  return cat(Buffer.from([0]), nulText(mime, 'latin1'), Buffer.from([3]), Buffer.from([0]), picture)
}

function id3File({ title, artists, album, picture }) {
  const frames = cat(
    // Encoding 0 carrying GBK: what Chinese taggers actually wrote.
    id3Frame('TIT2', cat(Buffer.from([0]), Buffer.from(title, 'latin1'))),
    id3Frame('TPE1', cat(Buffer.from([0]), nulText(artists, 'latin1'))),
    id3Frame('TALB', cat(Buffer.from([3]), nulText(album))),
    id3Frame('APIC', id3Apic('image/jpeg', picture)),
  )
  const header = cat(Buffer.from('ID3', 'latin1'), Buffer.from([3, 0, 0]), syncsafe(frames.length + 512))
  return cat(header, frames, Buffer.alloc(512))
}

// ── FLAC ─────────────────────────────────────────────────────────────

function flacBlock(type, last, body) {
  const head = Buffer.from([(last ? 0x80 : 0) | type, (body.length >> 16) & 0xff, (body.length >> 8) & 0xff, body.length & 0xff])
  return cat(head, body)
}

function flacPicture(mime, description, picture) {
  return cat(
    be32(3),
    be32(mime.length),
    Buffer.from(mime, 'latin1'),
    be32(Buffer.byteLength(description, 'utf8')),
    Buffer.from(description, 'utf8'),
    Buffer.alloc(16),
    be32(picture.length),
    picture,
  )
}

function vorbisComment(entries) {
  const vendor = Buffer.from('ske-verify', 'utf8')
  const parts = [le32(vendor.length), vendor, le32(entries.length)]
  for (const [key, value] of entries) {
    const entry = Buffer.from(`${key}=${value}`, 'utf8')
    parts.push(le32(entry.length), entry)
  }
  return cat(...parts)
}

function flacFile({ title, artists, album, picture }) {
  const streaminfo = cat(Buffer.from([0x10, 0x00, 0x10, 0x00]), Buffer.alloc(30))
  const comments = vorbisComment([
    ['TITLE', title],
    ...artists.map((artist) => ['ARTIST', artist]),
    ['ALBUM', album],
  ])
  return cat(
    Buffer.from('fLaC', 'latin1'),
    flacBlock(0, false, streaminfo),
    flacBlock(4, false, comments),
    flacBlock(6, true, flacPicture('image/jpeg', 'cover', picture)),
  )
}

// ── Ogg / Vorbis ─────────────────────────────────────────────────────

function oggPage({ headerType, sequence, lacing, body }) {
  const head = Buffer.alloc(27)
  head.write('OggS', 0, 'latin1')
  head[5] = headerType
  head.writeBigUInt64LE(0n, 6)
  head.writeUInt32LE(1, 14)
  head.writeUInt32LE(sequence, 18)
  head[26] = lacing.length
  return cat(head, Buffer.from(lacing), body)
}

function oggFile({ title, artists, album, picture }) {
  const identification = cat(Buffer.from([1]), Buffer.from('vorbis', 'latin1'), Buffer.alloc(23))
  const base64 = flacPicture('image/jpeg', 'cover', picture).toString('base64')
  const packet = cat(
    Buffer.from([3]),
    Buffer.from('vorbis', 'latin1'),
    vorbisComment([
      ['TITLE', title],
      ...artists.map((artist) => ['ARTIST', artist]),
      ['ALBUM', album],
      ['METADATA_BLOCK_PICTURE', base64],
    ]),
    Buffer.from([1]),
  )

  // The comment header is pushed past one page boundary on purpose: a picture
  // rides inside it, so real files split it across pages exactly like this.
  const split = 510
  if (packet.length <= split) throw new Error('comment packet too small to span pages')

  const rest = packet.subarray(split)
  const lacing = []
  for (let offset = 0; offset < rest.length; offset += 255) lacing.push(Math.min(255, rest.length - offset))
  if (rest.length % 255 === 0) lacing.push(0)

  return cat(
    oggPage({ headerType: 0x02, sequence: 0, lacing: [identification.length], body: identification }),
    oggPage({ headerType: 0x00, sequence: 1, lacing: [255, 255], body: packet.subarray(0, split) }),
    oggPage({ headerType: 0x01, sequence: 2, lacing, body: rest }),
  )
}

// ── MP4 / M4A ────────────────────────────────────────────────────────

function box(type, ...payload) {
  const body = cat(...payload)
  const head = Buffer.alloc(8)
  head.writeUInt32BE(body.length + 8)
  head.write(type, 4, 'latin1')
  return cat(head, body)
}

function dataBox(flags, payload) {
  return box('data', be32(flags), Buffer.alloc(4), payload)
}

function m4aFile({ title, artist, album, picture }) {
  const ilst = box(
    'ilst',
    box('\u00a9nam', dataBox(1, Buffer.from(title, 'utf8'))),
    box('\u00a9ART', dataBox(1, Buffer.from(artist, 'utf8'))),
    box('\u00a9alb', dataBox(1, Buffer.from(album, 'utf8'))),
    box('covr', dataBox(13, picture)),
  )
  // `moov` last: the layout every encoder that writes metadata after the audio
  // produces, and the reason the reader cannot stop at "first 256 KiB".
  return cat(
    box('ftyp', Buffer.from('M4A ', 'latin1'), be32(0), Buffer.from('M4A isom', 'latin1')),
    box('mdat', Buffer.alloc(4096, 0x11)),
    box('moov', box('udta', box('meta', Buffer.alloc(4), ilst))),
  )
}

// ── Server ───────────────────────────────────────────────────────────

function serve(files, honourRange) {
  const server = http.createServer((req, res) => {
    const name = decodeURIComponent(new URL(req.url, 'http://h').pathname.slice(1))
    const body = files[name]
    if (!body) {
      res.writeHead(404).end()
      return
    }

    const range = req.headers.range
    const match = honourRange && range && /bytes=(\d*)-(\d*)/.exec(range)
    if (match) {
      const start = match[1] ? Number(match[1]) : 0
      const end = match[2] ? Math.min(Number(match[2]), body.length - 1) : body.length - 1
      if (start >= body.length) {
        res.writeHead(416, { 'Content-Range': `bytes */${body.length}` }).end()
        return
      }
      res.writeHead(206, {
        'Accept-Ranges': 'bytes',
        'Content-Range': `bytes ${start}-${end}/${body.length}`,
        'Content-Length': end - start + 1,
        'Content-Type': 'application/octet-stream',
      })
      res.end(body.subarray(start, end + 1))
      return
    }

    res.writeHead(200, { 'Content-Length': body.length, 'Content-Type': 'application/octet-stream' })
    res.end(body)
  })

  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }))
  })
}

// ── Cases ────────────────────────────────────────────────────────────

const picture = fakeJpeg()
// 晴 and 天 in GBK: an id3v2.3 encoding-0 tag as written by older Chinese tools.
const gbkTitle = Buffer.from([0xc7, 0xe7, 0xcc, 0xec]).toString('latin1')

const cases = [
  {
    name: 'id3.mp3',
    body: id3File({ title: gbkTitle, artists: 'A\0B', album: '专辑', picture }),
    expected: { title: '晴天', artist: 'A / B', album: '专辑', cover: picture },
  },
  {
    name: 'tags.flac',
    body: flacFile({ title: 'FLAC 标题', artists: ['一号', '二号'], album: 'FLAC 专辑', picture }),
    expected: { title: 'FLAC 标题', artist: '一号 / 二号', album: 'FLAC 专辑', cover: picture },
  },
  {
    name: 'tags.ogg',
    body: oggFile({ title: 'Ogg 标题', artists: ['Ogg 艺术家'], album: 'Ogg 专辑', picture }),
    expected: { title: 'Ogg 标题', artist: 'Ogg 艺术家', album: 'Ogg 专辑', cover: picture },
  },
  {
    name: 'tags.m4a',
    body: m4aFile({ title: 'M4A 标题', artist: 'M4A 艺术家', album: 'M4A 专辑', picture }),
    expected: { title: 'M4A 标题', artist: 'M4A 艺术家', album: 'M4A 专辑', cover: picture },
  },
]

async function coverBytes(url) {
  const res = await fetch(url)
  return Buffer.from(await res.arrayBuffer())
}

async function run(honourRange) {
  const files = Object.fromEntries(cases.map((item) => [item.name, item.body]))
  const { server, port } = await serve(files, honourRange)

  try {
    for (const item of cases) {
      const label = `${honourRange ? 'Range' : '整段'} ${item.name}`
      const tags = await readAudioTags(`http://127.0.0.1:${port}/${item.name}`)

      check(`${label} title`, tags.title, item.expected.title)
      check(`${label} artist`, tags.artist, item.expected.artist)
      check(`${label} album`, tags.album, item.expected.album)
      check(`${label} cover 类型`, typeof tags.cover, 'string')

      if (!tags.cover) {
        failures += 1
        checks += 1
        console.error(`FAIL ${label} cover: 未提取到封面`)
      } else {
        const actual = await coverBytes(tags.cover)
        check(`${label} cover 字节`, actual.equals(item.expected.cover) ? 'same' : `${actual.length}B`, 'same')
        URL.revokeObjectURL(tags.cover)
      }

      // What the interface shows: tag values first, file name only as a gap.
      const url = `http://127.0.0.1:${port}/${item.name}`
      await ensureTags(url)
      const shown = describeTrack(url, item.name)
      check(`${label} 显示歌名`, shown.title, item.expected.title)
      check(`${label} 显示副标题`, shown.byline, `${item.expected.artist} · ${item.expected.album}`)
    }

    // A file without tags keeps the file name, minus the extension.
    const plain = describeTrack(`http://127.0.0.1:${port}/missing.mp3`, '晴天.mp3')
    check('无标签时回退文件名', plain.title, '晴天')
    check('无标签时没有副标题', plain.byline, '')
    check('文件名去扩展名', trackName('track.01.flac'), 'track.01')
    check('无扩展名保持不变', trackName('没有点'), '没有点')
  } finally {
    await new Promise((resolve) => server.close(resolve))
  }
}

await run(true)
await run(false)

console.log(`\n${checks - failures}/${checks} 项通过`)
process.exit(failures === 0 ? 0 : 1)
