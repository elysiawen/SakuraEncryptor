/**
 * Exercises the loopback endpoint the way a player would.
 *
 * Usage:
 *   node scripts/verify-http.mjs <container.ske> <password> <plaintext>
 *
 * Covers: full GET, 206 partial ranges, HEAD, unsatisfiable range, and the
 * token gate. This is the M0 acceptance check — if it passes, `<video>` and
 * mpv are both talking to a correct HTTP range server.
 */
import fs from 'node:fs/promises'
import process from 'node:process'
import { MediaRegistry } from '../src/main/media-router.js'
import { startServer } from '../src/main/server.js'

const [containerPath, password, plainPath] = process.argv.slice(2)
if (!containerPath || !password || !plainPath) {
  console.error('用法: node scripts/verify-http.mjs <container.ske> <password> <plaintext>')
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

const expected = await fs.readFile(plainPath)
const registry = new MediaRegistry()
const server = await startServer({ registry })

try {
  const info = await registry.open({
    target: containerPath,
    password,
    name: plainPath.split(/[\\/]/).pop() || 'file.bin',
  })
  const url = `${server.origin}/media/${info.id}?t=${server.token}`
  console.log(`端点: ${server.origin}/media/${info.id}`)

  // Full body.
  const full = await fetch(url)
  assert('全量 200', full.status === 200, `status=${full.status}`)
  assert('全量 Content-Type', !!full.headers.get('content-type'))
  assert('Accept-Ranges 存在', full.headers.get('accept-ranges') === 'bytes')
  assert('全量 Content-Length', Number(full.headers.get('content-length')) === expected.length)
  assert('全量字节一致', Buffer.from(await full.arrayBuffer()).equals(expected))

  // Partial ranges, including the two seek shapes a browser actually sends.
  const cases = [
    [0, 1023],
    [1024 * 1024 - 16, 1024 * 1024 + 16],
    [Math.max(0, expected.length - 2048), expected.length - 1],
  ]
  for (const [start, end] of cases) {
    const res = await fetch(url, { headers: { Range: `bytes=${start}-${end}` } })
    const body = Buffer.from(await res.arrayBuffer())
    assert(`206 [${start},${end}] 状态`, res.status === 206, `status=${res.status}`)
    assert(
      `206 [${start},${end}] Content-Range`,
      res.headers.get('content-range') === `bytes ${start}-${end}/${expected.length}`,
      res.headers.get('content-range') || 'null',
    )
    assert(`206 [${start},${end}] 长度`, body.length === end - start + 1, `${body.length}`)
    assert(`206 [${start},${end}] 字节一致`, body.equals(expected.subarray(start, end + 1)))
  }

  // Open-ended range (`bytes=N-`), which is what mpv uses to stream.
  const openEnded = await fetch(url, { headers: { Range: 'bytes=100-' } })
  const openBody = Buffer.from(await openEnded.arrayBuffer())
  assert('开放式 Range 状态', openEnded.status === 206)
  assert('开放式 Range 字节一致', openBody.equals(expected.subarray(100)))

  // HEAD must report the same geometry without a body.
  const head = await fetch(url, { method: 'HEAD' })
  assert('HEAD 200', head.status === 200, `status=${head.status}`)
  assert('HEAD Content-Length', Number(head.headers.get('content-length')) === expected.length)

  // Out-of-bounds range.
  const bad = await fetch(url, { headers: { Range: `bytes=${expected.length + 10}-` } })
  assert('越界 Range 返回 416', bad.status === 416, `status=${bad.status}`)

  // Token gate: the same path without the token must not leak bytes.
  const denied = await fetch(`${server.origin}/media/${info.id}`)
  assert('无 token 被拒绝', denied.status === 403, `status=${denied.status}`)

  const unknown = await fetch(`${server.origin}/media/does-not-exist?t=${server.token}`)
  assert('未知会话 404', unknown.status === 404, `status=${unknown.status}`)
} finally {
  await registry.closeAll()
  await server.close()
}

console.log(`\n${checks - failures}/${checks} 项通过`)
process.exit(failures === 0 ? 0 : 1)
