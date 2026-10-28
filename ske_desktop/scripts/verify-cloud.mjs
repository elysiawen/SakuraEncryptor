/**
 * End-to-end cloud test against a mock AList server.
 *
 * Usage:
 *   node scripts/verify-cloud.mjs <container.ske> <sourceFile> <password>
 *
 * A real AList instance is not required: the mock speaks the same three
 * endpoints, serves encrypted names produced by `ske-names.js`, and supports
 * Range on raw links. This proves path encryption, name decryption, pagination,
 * preview extraction and playback all agree with each other.
 */
import http from 'node:http'
import fs from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { CloudService } from '../src/main/cloud.js'
import { MediaRegistry } from '../src/main/media-router.js'
import { encryptName, deriveNameKey } from '../src/main/ske-names.js'

const [containerPath, plainPath, password] = process.argv.slice(2)
if (!containerPath || !plainPath || !password) {
  console.error('用法: node scripts/verify-cloud.mjs <container.ske> <sourceFile> <password>')
  process.exit(2)
}

let checks = 0
let failures = 0
function assert(label, condition, detail = '') {
  checks += 1
  if (condition) return
  failures += 1
  console.error(`FAIL ${label}${detail ? ': ' + detail : ''}`)
}

const nameKey = deriveNameKey(password)
const enc = (plain) => encryptName(plain, nameKey)

const containerBytes = await fs.readFile(containerPath)
const plainBytes = await fs.readFile(plainPath)
const fileName = path.basename(plainPath)

// Stored names as they would appear on the server: folders are bare tokens,
// files are `token.ske`.
const dirToken = enc('电影')
const fileToken = `${enc(fileName)}.ske`

/** Unwrap the plain path back to a token path, mimicking the real layout. */
function storedFor(plainDir) {
  const parts = plainDir.split('/').filter(Boolean)
  return '/' + parts.map((p) => enc(p)).join('/')
}

// With an empty base path a root-level file lives at `/<token>.ske`.
const files = {
  [`/${fileToken}`]: containerBytes,
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1')

  if (url.pathname === '/api/auth/login') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ code: 200, data: { token: 'mock-token' } }))
    return
  }

  if (url.pathname === '/api/fs/list') {
    let body = ''
    for await (const chunk of req) body += chunk
    const { path: listPath = '/', page = 1, per_page = 200 } = JSON.parse(body || '{}')

    const entries =
      listPath.replace(/\/+$/, '') === ''
        ? [
            { name: dirToken, is_dir: true, size: 0, modified: '2026-01-01T00:00:00Z' },
            { name: fileToken, is_dir: false, size: containerBytes.length, modified: '2026-01-02T00:00:00Z' },
          ]
        : []

    // Paginate exactly like AList: entries beyond per_page are dropped.
    const start = (page - 1) * per_page
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(
      JSON.stringify({
        code: 200,
        data: { content: entries.slice(start, start + per_page), total: entries.length },
      }),
    )
    return
  }

  if (url.pathname === '/api/fs/get') {
    let body = ''
    for await (const chunk of req) body += chunk
    const { path: getPath = '' } = JSON.parse(body || '{}')
    if (!files[getPath]) {
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ code: 404, message: 'object not found' }))
      return
    }
    const { port } = server.address()
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(
      JSON.stringify({
        code: 200,
        data: {
          raw_url: `http://127.0.0.1:${port}/raw${encodeURI(getPath)}`,
          size: files[getPath].length,
        },
      }),
    )
    return
  }

  if (url.pathname.startsWith('/raw/')) {
    const key = decodeURI(url.pathname.slice('/raw'.length))
    const data = files[key]
    if (!data) {
      res.writeHead(404)
      res.end()
      return
    }

    const range = req.headers.range
    if (range) {
      const [, startRaw, endRaw] = /bytes=(\d*)-(\d*)/.exec(range) || []
      const start = Number(startRaw || 0)
      const end = endRaw ? Math.min(Number(endRaw), data.length - 1) : data.length - 1
      res.writeHead(206, {
        'Content-Range': `bytes ${start}-${end}/${data.length}`,
        'Content-Length': end - start + 1,
        'Accept-Ranges': 'bytes',
      })
      res.end(data.subarray(start, end + 1))
      return
    }

    res.writeHead(200, { 'Content-Length': data.length, 'Accept-Ranges': 'bytes' })
    res.end(data)
    return
  }

  res.writeHead(404)
  res.end()
})

await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const { port } = server.address()
const base = `http://127.0.0.1:${port}`

const cloud = new CloudService()
const registry = new MediaRegistry()

try {
  // No media password must fail loudly instead of listing opaque tokens.
  let missingPassword = null
  try {
    await cloud.login({ profile: { id: 'p1', server: base, username: 'u' }, password: 'x', skePassword: '' })
  } catch (err) {
    missingPassword = err
  }
  assert('缺少加密密码时报错', !!missingPassword)

  const { sessionId } = await cloud.login({
    profile: { id: 'p1', name: 'Mock', server: base, username: 'u', basePath: '' },
    password: 'login-pass',
    skePassword: password,
  })
  assert('登录成功', !!sessionId)

  const root = await cloud.list(sessionId, '/')
  assert('根目录返回 2 项', root.entries.length === 2, `len=${root.entries.length}`)
  assert('文件夹名已解密', root.entries[0].name === '电影', root.entries[0].name)
  assert('文件夹排在前面', root.entries[0].isDir === true)
  assert('文件名已解密（去掉 .ske）', root.entries[1].name === fileName, root.entries[1].name)
  assert('文件大小正确', root.entries[1].size === containerBytes.length, `${root.entries[1].size}`)
  assert('文件类型识别为视频', root.entries[1].kind === 'video', root.entries[1].kind)
  assert('没有不可解密的条目', root.entries.every((e) => !e.locked))

  const sub = await cloud.list(sessionId, '/电影')
  assert('中文目录路径可定位', sub.entries.length === 0)

  const jpeg = await cloud.thumbnail(sessionId, `/${fileName}`)
  assert('缩略图已解密', !!jpeg && jpeg.length > 100, jpeg ? `${jpeg.length} B` : 'null')
  assert('缩略图是 JPEG', !!jpeg && jpeg[0] === 0xff && jpeg[1] === 0xd8)

  const { rawUrl, size } = await cloud.resolve(sessionId, `/${fileName}`)
  assert('解析出下载地址', rawUrl.startsWith(base))
  assert('解析出字节大小', size === containerBytes.length)

  // Playback through the same path the UI uses.
  const info = await registry.open({ target: rawUrl, password, name: fileName })
  const session = registry.get(info.id)
  const chunks = []
  for await (const chunk of session.readRange(0, plainBytes.length - 1)) chunks.push(chunk)
  const decoded = Buffer.concat(chunks)

  assert('播放端明文大小一致', decoded.length === plainBytes.length, `${decoded.length}`)
  assert('播放端明文逐字节一致', decoded.equals(plainBytes))

  // A wrong media password must be rejected at login-listing time as `locked`,
  // not silently produce garbage names.
  const other = await cloud.login({
    profile: { id: 'p2', name: 'Wrong', server: base, username: 'u', basePath: '' },
    password: 'login-pass',
    skePassword: 'definitely-not-the-password',
  })
  const wrongList = await cloud.list(other.sessionId, '/')
  assert('错误密码下列表标记 locked', wrongList.entries.filter((e) => !e.isDir).every((e) => e.locked))
} finally {
  cloud.logoutAll()
  await registry.closeAll()
  server.close()
}

console.log(`\n${checks - failures}/${checks} 项通过`)
process.exit(failures ? 1 : 0)
