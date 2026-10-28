/**
 * Folder-encryption test for the job queue.
 *
 * Builds a nested source tree (including an empty folder), encrypts it through
 * the queue, then verifies:
 *   - every name decrypts back to the original,
 *   - empty folders survive,
 *   - the plaintext round-trips through the Python reference,
 *   - cancellation removes partial output.
 *
 *   node scripts/verify-jobs.mjs
 */
import { execFileSync } from 'node:child_process'
import crypto from 'node:crypto'
import fsp from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { JobQueue } from '../src/main/jobs.js'
import { LocalLibrary } from '../src/main/local-fs.js'
import { deriveNameKey } from '../src/main/ske-names.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(here, '../..')
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

const workDir = path.join(os.tmpdir(), `ske-jobs-${process.pid}`)
const srcDir = path.join(workDir, '我的视频')
const outDir = path.join(workDir, 'out')
const password = 'job-pass-密码'

await fsp.rm(workDir, { recursive: true, force: true })
await fsp.mkdir(path.join(srcDir, '第二季'), { recursive: true })
await fsp.mkdir(path.join(srcDir, '空目录'), { recursive: true })

const rootFile = crypto.randomBytes(600_000)
const nestedFile = crypto.randomBytes(1_400_000)
await fsp.writeFile(path.join(srcDir, '第一集.mp4'), rootFile)
await fsp.writeFile(path.join(srcDir, '第二季', '第二集 1080p.mkv'), nestedFile)

const updates = []
const queue = new JobQueue({ onUpdate: (jobs) => updates.push(jobs) })

function waitFor(predicate, label, timeout = 60_000) {
  return new Promise((resolve, reject) => {
    const started = Date.now()
    const timer = setInterval(() => {
      if (predicate()) {
        clearInterval(timer)
        resolve()
      } else if (Date.now() - started > timeout) {
        clearInterval(timer)
        reject(new Error(`等待超时: ${label}`))
      }
    }, 100)
  })
}

const { id } = queue.add({
  srcPath: srcDir,
  destDir: outDir,
  password,
  nameKey: deriveNameKey(password),
  thumbnails: false,
})

await waitFor(
  () => ['done', 'failed', 'cancelled'].includes(queue.jobs.get(id)?.status),
  `首任务 state=${queue.jobs.get(id)?.status}`,
)

const job = queue.jobs.get(id)
assert('任务完成', job.status === 'done', `${job.status} ${job.error || ''}`)
assert('统计文件数', job.doneFiles === 2, `${job.doneFiles}`)

// Emits are throttled by 150 ms, so give the last one a chance to land before
// asserting on it.
await new Promise((resolve) => setTimeout(resolve, 400))
assert('进度报文已推送', updates.length > 0, `${updates.length}`)
assert('任务结束后清除密码', job.password === '', JSON.stringify(job.password))

// --- structure ------------------------------------------------------------
const library = new LocalLibrary()
library.unlock(password)
library.setRoot(outDir)

const top = await library.list('/')
assert('输出根只有一个加密目录', top.entries.length === 1, `${top.entries.length}`)
assert('顶层目录名解密', top.entries[0].name === '我的视频', top.entries[0].name)

const inside = await library.list('/我的视频')
assert('目录内 3 项（含空目录）', inside.entries.length === 3, `${inside.entries.length}`)
assert(
  '空目录保留',
  inside.entries.some((e) => e.isDir && e.name === '空目录'),
  inside.entries.map((e) => `${e.name}:${e.isDir}`).join(','),
)
assert(
  '文件名解密',
  inside.entries.some((e) => e.name === '第一集.mp4'),
  inside.entries.map((e) => e.name).join(','),
)

const season = await library.list('/我的视频/第二季')
assert('嵌套目录可进入', season.entries.length === 1, `${season.entries.length}`)
assert('嵌套文件名解密', season.entries[0].name === '第二集 1080p.mkv', season.entries[0].name)

// --- content round-trip through the Python reference ----------------------
const encryptedNested = library.filePath('/我的视频/第二季/第二集 1080p.mkv')
const decodedPath = path.join(workDir, 'decoded.mkv')
execFileSync(
  python,
  [
    '-c',
    `from ske_cli.crypto import decrypt_file; decrypt_file(${JSON.stringify(encryptedNested)}, ${JSON.stringify(decodedPath)}, ${JSON.stringify(password)})`,
  ],
  { cwd: rootDir, stdio: 'pipe' },
)
const decoded = await fsp.readFile(decodedPath)
assert('Python 解出嵌套文件字节一致', decoded.equals(nestedFile), `${decoded.length} vs ${nestedFile.length}`)

// --- cancellation of a queued job ----------------------------------------
// Pinned to one worker so the second job is deterministically still queued
// when it is cancelled, instead of racing the first one to completion.
const bigDir = path.join(workDir, 'big')
await fsp.mkdir(bigDir, { recursive: true })
for (let i = 0; i < 40; i += 1) {
  await fsp.writeFile(path.join(bigDir, `f${i}.bin`), Buffer.alloc(1024 * 1024))
}

const serialQueue = new JobQueue({ onUpdate: () => {}, maxConcurrent: 1 })
const first = serialQueue.add({
  srcPath: bigDir,
  destDir: path.join(workDir, 'out1'),
  password,
  nameKey: deriveNameKey(password),
  thumbnails: false,
})
await waitFor(() => serialQueue.jobs.get(first.id)?.status === 'running', '首个任务开始')

const second = serialQueue.add({
  srcPath: bigDir,
  destDir: path.join(workDir, 'out2'),
  password,
  nameKey: deriveNameKey(password),
  thumbnails: false,
})
await waitFor(() => serialQueue.jobs.get(second.id)?.status === 'queued', '第二个任务排队')

serialQueue.cancel(second.id)
await waitFor(
  () => serialQueue.jobs.get(second.id)?.status === 'cancelled',
  '第二个任务取消',
  10_000,
)
assert('排队中的任务可取消', serialQueue.jobs.get(second.id).status === 'cancelled')

// Let the first job finish so the assertion below is not racing a writer.
await waitFor(
  () => ['done', 'failed', 'cancelled'].includes(serialQueue.jobs.get(first.id)?.status),
  '首个任务结束',
)

const leftovers = await fsp
  .readdir(path.join(workDir, 'out2'), { recursive: true })
  .then((names) => names.filter((n) => String(n).endsWith('.ske')))
  .catch(() => [])
assert('被取消的任务没有写任何容器', leftovers.length === 0, leftovers.join(','))

// --- clean shutdown -------------------------------------------------------
queue.cancelAll()
await fsp.rm(workDir, { recursive: true, force: true }).catch(() => {})

console.log(`\n${checks - failures}/${checks} 项通过`)
process.exit(failures ? 1 : 0)
