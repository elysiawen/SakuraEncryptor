/**
 * Cross-implementation check for the container writer.
 *
 * Encrypts with the Node writer, then decrypts with the Python reference and
 * re-reads with the Node reader. A pass means all three agree byte for byte.
 *
 *   node scripts/verify-writer.mjs
 */
import { execFileSync } from 'node:child_process'
import crypto from 'node:crypto'
import fsp from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { encryptFile } from '../src/main/ske-writer.js'
import { MediaRegistry } from '../src/main/media-router.js'
import { readThumbnail } from '../src/main/ske-format.js'
import { LocalSource } from '../src/main/sources.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(here, '../..')
const tmpDir = path.join(here, '..', '.tmp')
await fsp.mkdir(tmpDir, { recursive: true })

const python = process.platform === 'win32'
  ? path.join(rootDir, '.venv', 'Scripts', 'python.exe')
  : path.join(rootDir, '.venv', 'bin', 'python')

let checks = 0
let failures = 0
function assert(label, condition, detail = '') {
  checks += 1
  if (condition) return
  failures += 1
  console.error(`FAIL ${label}${detail ? ': ' + detail : ''}`)
}

async function makeSample(size, name) {
  const filePath = path.join(tmpDir, name)
  const chunks = []
  for (let written = 0; written < size; written += 65536) {
    chunks.push(crypto.randomBytes(Math.min(65536, size - written)))
  }
  const data = Buffer.concat(chunks)
  await fsp.writeFile(filePath, data)
  return { filePath, data }
}

/** Ask the Python reference to decrypt, so the check is not self-referential. */
function pythonDecrypt(containerPath, outPath, password) {
  execFileSync(
    python,
    [
      '-c',
      `from ske_cli.crypto import decrypt_file; decrypt_file(${JSON.stringify(containerPath)}, ${JSON.stringify(outPath)}, ${JSON.stringify(password)})`,
    ],
    { cwd: rootDir, stdio: 'pipe' },
  )
  return fsp.readFile(outPath)
}

function pythonThumb(containerPath, password) {
  const out = execFileSync(
    python,
    [
      '-c',
      `import sys,base64\nfrom ske_cli.crypto import decrypt_thumbnail\nd=decrypt_thumbnail(${JSON.stringify(containerPath)}, ${JSON.stringify(password)})\nsys.stdout.write(base64.b64encode(d or b'').decode())`,
    ],
    { cwd: rootDir, stdio: 'pipe' },
  )
  return Buffer.from(out.toString(), 'base64')
}

const password = 'writer-密码-001'

// --- case 1: exactly one block (boundary) ---------------------------------
{
  const { filePath, data } = await makeSample(1024 * 1024, 'w-block.bin')
  const container = path.join(tmpDir, 'w-block.ske')

  await encryptFile({ src: filePath, dst: container, password })
  const stat = await fsp.stat(container)

  // 55 header + 22 options + 22 empty envelope + 1 block of (plain + 16 tag)
  assert('单块容器尺寸', stat.size === 99 + data.length + 16, `${stat.size}`)

  const decoded = await pythonDecrypt(container, path.join(tmpDir, 'w-block.out'), password)
  assert('单块 Python 解出字节一致', decoded.equals(data))

  const registry = new MediaRegistry()
  const info = await registry.open({ target: container, password, name: 'w-block.bin' })
  assert('单块 Node 读回尺寸', info.size === data.length, `${info.size}`)
  await registry.closeAll()
}

// --- case 2: three blocks + preview --------------------------------------
{
  const { filePath, data } = await makeSample(2_400_000, 'w-multi.bin')
  const container = path.join(tmpDir, 'w-multi.ske')
  const thumbnail = crypto.randomBytes(3000)

  let progressCalls = 0
  let lastProcessed = 0
  await encryptFile({
    src: filePath,
    dst: container,
    password,
    thumbnail,
    onProgress: (processed) => {
      progressCalls += 1
      lastProcessed = processed
    },
  })

  const stat = await fsp.stat(container)
  assert('多块容器尺寸', stat.size === 99 + 3000 + 16 + data.length + 3 * 16, `${stat.size}`)
  assert('进度回调被调用', progressCalls === 3, `${progressCalls}`)
  assert('进度累计到文件大小', lastProcessed === data.length, `${lastProcessed}`)

  const decoded = await pythonDecrypt(container, path.join(tmpDir, 'w-multi.out'), password)
  assert('多块 Python 解出字节一致', decoded.equals(data))

  const thumb = pythonThumb(container, password)
  assert('缩略图可被 Python 解密', thumb.equals(thumbnail), `${thumb.length} vs ${thumbnail.length}`)

  const source = new LocalSource(container)
  await source.open()
  const nodeThumb = await readThumbnail(source, password)
  await source.close()
  assert('缩略图可被 Node 解密', !!nodeThumb && nodeThumb.equals(thumbnail))
}

// --- case 3: empty file ---------------------------------------------------
{
  const filePath = path.join(tmpDir, 'w-empty.bin')
  await fsp.writeFile(filePath, Buffer.alloc(0))
  const container = path.join(tmpDir, 'w-empty.ske')

  await encryptFile({ src: filePath, dst: container, password })
  const stat = await fsp.stat(container)
  assert('空容器为 99 字节', stat.size === 99, `${stat.size}`)

  const decoded = await pythonDecrypt(container, path.join(tmpDir, 'w-empty.out'), password)
  assert('空容器 Python 解出 0 字节', decoded.length === 0)
}

// --- case 4: cancellation leaves no container ----------------------------
{
  const { filePath } = await makeSample(3_000_000, 'w-cancel.bin')
  const container = path.join(tmpDir, 'w-cancel.ske')
  const controller = new AbortController()

  let rejected = false
  try {
    await encryptFile({
      src: filePath,
      dst: container,
      password,
      signal: controller.signal,
      onProgress: () => controller.abort(),
    })
  } catch (err) {
    rejected = /取消/.test(err.message)
  }

  assert('取消时抛出', rejected)
  const exists = await fsp
    .stat(container)
    .then(() => true)
    .catch(() => false)
  assert('取消后不留半成品', !exists)
}

// --- case 5: wrong password cannot open a written container --------------
{
  const { filePath } = await makeSample(2048, 'w-pass.bin')
  const container = path.join(tmpDir, 'w-pass.ske')
  await encryptFile({ src: filePath, dst: container, password })

  const registry = new MediaRegistry()
  let rejected = false
  try {
    await registry.open({ target: container, password: 'wrong', name: 'w-pass.bin' })
  } catch (err) {
    rejected = /Wrong password/i.test(err.message)
  }
  assert('写入的容器拒绝错误密码', rejected)
  await registry.closeAll()
}

console.log(`\n${checks - failures}/${checks} 项通过`)
process.exit(failures ? 1 : 0)
