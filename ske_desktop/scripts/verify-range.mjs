/**
 * Byte-exact regression check for the Node reader.
 *
 * Usage:
 *   node scripts/verify-range.mjs <container.ske> <password> <plaintext>
 *
 * The plaintext file is the original the container was made from. Every range
 * below is decrypted through the same code path the HTTP endpoint uses, so a
 * pass here means `<video>` will see exactly the right bytes at any offset.
 */
import fs from 'node:fs/promises'
import process from 'node:process'
import { MediaRegistry } from '../src/main/media-router.js'
import { CHUNK_SIZE } from '../src/main/ske-format.js'

const [containerPath, password, plainPath] = process.argv.slice(2)
if (!containerPath || !password || !plainPath) {
  console.error('用法: node scripts/verify-range.mjs <container.ske> <password> <plaintext>')
  process.exit(2)
}

let failures = 0
let checks = 0

function check(label, actual, expected) {
  checks += 1
  if (!actual.equals(expected)) {
    failures += 1
    console.error(`FAIL ${label}: got ${actual.length} B, expected ${expected.length} B`)
  }
}

const registry = new MediaRegistry()
const expected = await fs.readFile(plainPath)

try {
  const info = await registry.open({
    target: containerPath,
    password,
    name: plainPath.split(/[\\/]/).pop(),
  })
  const session = registry.get(info.id)

  console.log(`容器: ${containerPath}`)
  console.log(`明文: ${expected.length} B，报告: ${info.size} B，块数: ${session.blocks}`)

  if (info.size !== expected.length) {
    failures += 1
    console.error(`FAIL 尺寸不一致: ${info.size} != ${expected.length}`)
  }

  async function readRange(start, end) {
    const chunks = []
    for await (const chunk of session.readRange(start, end)) chunks.push(chunk)
    return Buffer.concat(chunks)
  }

  const size = expected.length
  if (size === 0) {
    console.log('空文件：没有正文块需要校验')
  } else {
    const boundaries = [
      ['首字节', 0, 0],
      ['首块尾部', CHUNK_SIZE - 1, CHUNK_SIZE - 1],
      ['跨首块', CHUNK_SIZE - 8, CHUNK_SIZE + 8],
      ['完整第一块', 0, Math.min(CHUNK_SIZE, size) - 1],
      ['最后字节', size - 1, size - 1],
      ['尾段 4KB', Math.max(0, size - 4096), size - 1],
    ]

    for (const [label, start, end] of boundaries) {
      if (start >= size) continue
      const actual = await readRange(start, Math.min(end, size - 1))
      check(`${label} [${start},${Math.min(end, size - 1)}]`, actual, expected.subarray(start, Math.min(end, size - 1) + 1))
    }

    // Random windows: this is what seeking does, over and over.
    for (let i = 0; i < 30; i += 1) {
      const start = Math.floor(Math.random() * size)
      const end = Math.min(size - 1, start + Math.floor(Math.random() * 300_000))
      const actual = await readRange(start, end)
      check(`随机 [${start},${end}]`, actual, expected.subarray(start, end + 1))
    }
  }

  // Wrong password must be rejected before any block is touched.
  const wrong = await registry
    .open({ target: containerPath, password: `${password}-nope`, name: 'x.bin' })
    .then(() => null)
    .catch((err) => err)
  checks += 1
  if (!wrong || !/Wrong password/i.test(wrong.message)) {
    failures += 1
    console.error(`FAIL 错误密码未被拒绝: ${wrong ? wrong.message : '意外成功'}`)
  } else {
    console.log('错误密码被 KEY_CHECK 正确拒绝')
  }
} finally {
  await registry.closeAll()
}

console.log(`\n${checks - failures}/${checks} 项通过`)
process.exit(failures === 0 ? 0 : 1)
