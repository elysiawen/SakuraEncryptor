/**
 * Download-path smoke test.
 *
 * Encrypts a real container into a temp folder, then downloads it back through
 * the same `MediaRegistry` stream the player uses. What is being checked is that
 * a download is byte-identical to the original plaintext — a decrypting player
 * can survive a wrong block and a download cannot.
 *
 *   node node_modules/electron/cli.js scripts/verify-downloads.mjs
 */
import { app } from 'electron'
import fsp from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { MediaRegistry } from '../src/main/media-router.js'
import { LocalLibrary } from '../src/main/local-fs.js'
import { DownloadQueue } from '../src/main/downloads.js'
import { encryptFile } from '../src/main/ske-writer.js'
import { deriveNameKey, encryptName } from '../src/main/ske-names.js'

const PASSWORD = 'download-pass'
const sandboxDir = path.join(os.tmpdir(), `ske-downloads-${process.pid}`)
const libraryDir = path.join(sandboxDir, 'library')
const outputDir = path.join(sandboxDir, 'out')

const checks = []
function assert(label, condition, detail = '') {
  checks.push({ label, ok: !!condition, detail })
}

setTimeout(() => {
  console.error('FAIL 超时退出')
  app.exit(1)
}, 45_000)

/** Wait until `predicate(snapshot)` holds, or give up after `timeout`. */
async function waitFor(queue, predicate, timeout = 20_000) {
  const started = Date.now()
  for (;;) {
    const snapshot = queue.snapshot()
    if (predicate(snapshot)) return snapshot
    if (Date.now() - started > timeout) throw new Error('等待下载完成超时')
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
}

app.whenReady().then(async () => {
  const registry = new MediaRegistry()
  const localLibrary = new LocalLibrary()

  try {
    await fsp.mkdir(libraryDir, { recursive: true })
    await fsp.mkdir(outputDir, { recursive: true })

    const plain = Buffer.from('Sakura Encryptor download round-trip\n'.repeat(4096), 'utf8')
    const srcPath = path.join(sandboxDir, 'sample.txt')
    await fsp.writeFile(srcPath, plain)

    const nameKey = deriveNameKey(PASSWORD)
    const container = path.join(libraryDir, `${encryptName('sample.txt', nameKey)}.ske`)
    await encryptFile({ src: srcPath, dst: container, password: PASSWORD })

    localLibrary.unlock(PASSWORD)
    localLibrary.setRoot(libraryDir)
    const listing = await localLibrary.list('/')
    const entry = listing.entries.find((item) => item.name === 'sample.txt')
    assert('本地列表显示解密后的文件名', !!entry, JSON.stringify(listing.entries.map((e) => e.name)))

    const queue = new DownloadQueue({
      registry,
      // The download path only calls cloud for cloud-mode jobs.
      cloud: { get: () => { throw new Error('unused') }, resolve: () => { throw new Error('unused') } },
      localLibrary,
    })

    queue.add({ mode: 'local', path: entry.path, name: entry.name, destDir: outputDir })
    const first = await waitFor(queue, (snapshot) => snapshot[0]?.status === 'done' || snapshot[0]?.status === 'failed')

    assert('下载任务完成', first[0]?.status === 'done', first[0]?.error || first[0]?.status)
    assert('记录了明文总长度', first[0]?.totalBytes === plain.length, `actual=${first[0]?.totalBytes}`)
    assert('进度到达 100%', first[0]?.doneBytes === plain.length, `actual=${first[0]?.doneBytes}`)

    const outPath = first[0]?.outPath
    const written = outPath ? await fsp.readFile(outPath) : Buffer.alloc(0)
    assert('落盘文件名是明文名', path.basename(outPath || '') === 'sample.txt', path.basename(outPath || ''))
    assert('下载内容与原文一致', written.equals(plain), `written=${written.length}`)

    // A second download of the same name must not clobber the first one.
    queue.add({ mode: 'local', path: entry.path, name: entry.name, destDir: outputDir })
    const second = await waitFor(queue, (snapshot) => snapshot.length === 2 && snapshot.every((job) => job.status === 'done'))
    const names = second.map((job) => path.basename(job.outPath)).sort()
    assert('同名下载自动改名', names.join(',') === 'sample (2).txt,sample.txt', names.join(','))

    // Cancelling a queued job must remove it without touching the disk.
    const cancelled = queue.add({ mode: 'local', path: entry.path, name: 'cancel-me.txt', destDir: outputDir })
    assert('取消排队中的任务', queue.cancel(cancelled.id) === true)
    const afterCancel = await waitFor(queue, (snapshot) => snapshot.find((job) => job.id === cancelled.id)?.status === 'cancelled')
    assert('取消后状态为 cancelled', afterCancel.find((job) => job.id === cancelled.id)?.status === 'cancelled')

    const cleared = queue.clearFinished()
    assert('清理已完成任务', cleared.length === 0, `left=${cleared.length}`)
  } catch (err) {
    assert('测试执行未抛异常', false, err.message)
  } finally {
    await registry.closeAll()
    await fsp.rm(sandboxDir, { recursive: true, force: true }).catch(() => {})
  }

  const failed = checks.filter((c) => !c.ok)
  for (const check of checks) {
    console.log(`${check.ok ? 'PASS' : 'FAIL'}  ${check.label}${check.detail ? '  → ' + check.detail : ''}`)
  }
  console.log(`\n${checks.length - failed.length}/${checks.length} 项通过`)

  app.exit(failed.length ? 1 : 0)
})
