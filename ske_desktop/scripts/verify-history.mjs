/**
 * Transfer history test: what survives a restart, and what must not.
 *
 * Usage:
 *   node scripts/verify-history.mjs
 *
 * The queues are exercised through the same entry points `index.js` uses, so
 * this covers the wiring as well as the file: restore, remember on settle,
 * clearing from the UI, the row cap, and a corrupt file on disk.
 */
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { DownloadQueue } from '../src/main/downloads.js'
import { TransferHistory } from '../src/main/transfer-history.js'
import { UploadQueue } from '../src/main/uploads.js'

let checks = 0
let failures = 0
function assert(label, condition, detail = '') {
  checks += 1
  if (condition) return
  failures += 1
  console.error(`FAIL ${label}${detail ? ': ' + detail : ''}`)
}

const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'ske-verify-history-'))
const filePath = path.join(tempDir, 'transfers.json')

function uploadRecord(overrides = {}) {
  return {
    kind: 'upload',
    id: 'u1',
    name: 'movie.mp4.ske',
    dir: '/电影',
    encrypt: true,
    status: 'done',
    phase: 'storing',
    totalBytes: 2048,
    uploadBytes: 2100,
    encryptRatio: 1,
    sentBytes: 2100,
    storedBytes: 2100,
    error: '',
    startedAt: 1000,
    finishedAt: 2000,
    ...overrides,
  }
}

function downloadRecord(overrides = {}) {
  return {
    kind: 'download',
    id: 'd1',
    name: 'movie.mp4',
    mode: 'cloud',
    sourcePath: '/电影/movie.mp4.ske',
    destDir: 'D:/downloads',
    outPath: 'D:/downloads/movie.mp4',
    status: 'done',
    totalBytes: 2048,
    doneBytes: 2048,
    error: '',
    startedAt: 1000,
    finishedAt: 3000,
    ...overrides,
  }
}

try {
  // ── 1. Write, reload, read back ──
  {
    const history = new TransferHistory({ filePath })
    await history.load()

    history.remember(uploadRecord())
    history.remember(downloadRecord())
    history.flush()
    assert('历史文件已写入', await fs.stat(filePath).then(() => true, () => false))

    const reopened = new TransferHistory({ filePath })
    await reopened.load()
    assert('上传记录能读回', reopened.recordsFor('upload').length === 1)
    assert('下载记录能读回', reopened.recordsFor('download').length === 1)
    assert(
      '读回的字段完整',
      reopened.recordsFor('upload')[0]?.storedBytes === 2100 &&
        reopened.recordsFor('download')[0]?.outPath === 'D:/downloads/movie.mp4',
    )
  }

  // ── 2. Only settled rows are kept ──
  {
    const history = new TransferHistory({ filePath: path.join(tempDir, 'settled.json') })
    await history.load()
    history.remember(uploadRecord({ id: 'run', status: 'running' }))
    history.remember(uploadRecord({ id: 'queue', status: 'queued' }))
    assert('未结束的任务不写入历史', history.records.length === 0, `count=${history.records.length}`)

    history.remember(uploadRecord({ id: 'fail', status: 'failed', error: 'boom' }))
    history.remember(uploadRecord({ id: 'stop', status: 'cancelled' }))
    assert('失败与取消也算记录', history.records.length === 2, `count=${history.records.length}`)
  }

  // ── 3. Same id replaces instead of duplicating ──
  {
    const history = new TransferHistory({ filePath: path.join(tempDir, 'replace.json') })
    await history.load()
    history.remember(uploadRecord())
    history.remember(uploadRecord({ status: 'failed', error: 'later' }))
    assert('同一任务只保留一行', history.records.length === 1, `count=${history.records.length}`)
    assert('保留的是最新状态', history.records[0]?.status === 'failed')
  }

  // ── 4. Row cap keeps the newest ──
  {
    const history = new TransferHistory({
      filePath: path.join(tempDir, 'limit.json'),
      limit: 3,
    })
    await history.load()
    for (let i = 0; i < 6; i += 1) {
      history.remember(uploadRecord({ id: `row${i}`, finishedAt: 1000 + i }))
    }
    assert('超出上限会裁剪', history.records.length === 3, `count=${history.records.length}`)
    assert(
      '保留最新三条',
      history.records.map((r) => r.id).join(',') === 'row5,row4,row3',
      history.records.map((r) => r.id).join(','),
    )
  }

  // ── 5. Corrupt file does not take the launch down ──
  {
    const corruptPath = path.join(tempDir, 'corrupt.json')
    await fs.writeFile(corruptPath, '{ not json')
    const history = new TransferHistory({ filePath: corruptPath })
    await history.load()
    assert('损坏的文件被忽略', history.records.length === 0)
    const files = await fs.readdir(tempDir)
    assert('损坏的文件留了备份', files.some((name) => name.startsWith('corrupt.json.corrupt-')), files.join(','))
  }

  // ── 6. The queues restore rows and clear them from the file ──
  {
    const queuePath = path.join(tempDir, 'queue.json')
    const history = new TransferHistory({ filePath: queuePath })
    await history.load()
    history.remember(uploadRecord())
    history.remember(downloadRecord())
    history.flush()

    const loaded = new TransferHistory({ filePath: queuePath })
    await loaded.load()

    const uploads = new UploadQueue({ cloud: {}, history: loaded })
    const downloads = new DownloadQueue({ registry: {}, cloud: {}, localLibrary: {}, history: loaded })
    uploads.restore(loaded.recordsFor('upload'))
    downloads.restore(loaded.recordsFor('download'))

    const uploadRow = uploads.snapshot()[0]
    const downloadRow = downloads.snapshot()[0]
    assert('上传队列恢复了历史行', uploadRow?.id === 'u1' && uploadRow?.status === 'done')
    assert('恢复的上传行没有凭据', uploadRow?.target === undefined)
    assert('下载队列恢复了历史行', downloadRow?.id === 'd1' && downloadRow?.outPath === 'D:/downloads/movie.mp4')

    uploads.clearFinished()
    assert('清空界面同时清空内存', uploads.snapshot().length === 0)

    const afterUploadClear = new TransferHistory({ filePath: queuePath })
    await afterUploadClear.load()
    assert(
      '清空上传不会动下载记录',
      afterUploadClear.recordsFor('upload').length === 0 &&
        afterUploadClear.recordsFor('download').length === 1,
      afterUploadClear.records.map((r) => `${r.kind}:${r.id}`).join(','),
    )

    downloads.clearFinished()
    assert('下载也能清空', downloads.snapshot().length === 0)

    const afterClear = new TransferHistory({ filePath: queuePath })
    await afterClear.load()
    assert('清空界面同时清空磁盘', afterClear.records.length === 0, `count=${afterClear.records.length}`)
  }

  // ── 7. Quitting seals the file ──
  {
    const stopPath = path.join(tempDir, 'stop.json')
    const history = new TransferHistory({ filePath: stopPath })
    await history.load()

    // Recorded, but the debounced write has not run yet — exactly the window a
    // quit has to cover.
    history.remember(uploadRecord({ id: 'before-quit' }))
    history.stop()
    // Everything after is this quit cancelling the queues.
    history.remember(downloadRecord({ id: 'cancelled-by-quit', status: 'cancelled' }))

    const reopened = new TransferHistory({ filePath: stopPath })
    await reopened.load()
    assert(
      '退出前刚完成的记录已落盘',
      reopened.recordsFor('upload').some((record) => record.id === 'before-quit'),
      reopened.records.map((r) => r.id).join(','),
    )
    assert(
      '退出时被取消的任务不进历史',
      reopened.recordsFor('download').length === 0,
      reopened.records.map((r) => r.id).join(','),
    )
  }

  // ── 8b. One row at a time: the record alone, or the file with it ──
  {
    const rowPath = path.join(tempDir, 'row.json')
    const history = new TransferHistory({ filePath: rowPath })
    await history.load()

    const downloaded = path.join(tempDir, 'downloaded.mp4')
    const keptFile = path.join(tempDir, 'kept.mp4')
    await fs.writeFile(downloaded, 'payload')
    await fs.writeFile(keptFile, 'payload')

    history.remember(uploadRecord({ id: 'u-keep', profileId: 'p1', remoteName: 'keep.ske' }))
    history.remember(
      uploadRecord({ id: 'u-del', profileId: 'p1', remoteName: 'gone.ske', finishedAt: 2100 }),
    )
    history.remember(downloadRecord({ id: 'd-del', outPath: downloaded }))
    history.remember(downloadRecord({ id: 'd-keep', outPath: keptFile, finishedAt: 4000 }))

    const uploads = new UploadQueue({ cloud: {}, history })
    const downloads = new DownloadQueue({
      registry: {},
      cloud: {},
      localLibrary: {},
      history,
    })
    uploads.restore(history.recordsFor('upload'))
    downloads.restore(history.recordsFor('download'))

    assert(
      '上传行保留了云端文件名',
      uploads.snapshot().find((row) => row.id === 'u-del')?.remoteName === 'gone.ske',
    )

    // Record only: nothing on disk or in the cloud is touched.
    assert('删记录返回成功', uploads.forget('u-del') === true)
    assert('删掉的行从列表消失', uploads.snapshot().length === 1)
    assert('历史里这一行也没了', history.recordsFor('upload').every((row) => row.id !== 'u-del'))
    assert('另一行原样保留', history.recordsFor('upload').some((row) => row.id === 'u-keep'))

    // File too: only the row's own file goes.
    assert('删下载文件返回成功', (await downloads.deleteFile('d-del')) === true)
    assert(
      '本地文件被删掉',
      !(await fs.stat(downloaded).then(
        () => true,
        () => false,
      )),
    )
    assert(
      '别人的文件不动',
      await fs.stat(keptFile).then(
        () => true,
        () => false,
      ),
    )
    assert('对应的记录也删了', downloads.snapshot().every((row) => row.id !== 'd-del'))
    assert('另一条下载记录还在', downloads.snapshot().some((row) => row.id === 'd-keep'))

    // Guards: an unknown id, and a row that has not settled.
    assert('未知的行删不掉', uploads.forget('nope') === false)
    const liveUploads = new UploadQueue({ cloud: {}, history: null })
    // `restore` maps an unsettled row to `failed` on purpose; a live one only
    // exists inside a running queue, so it is planted here directly.
    liveUploads.jobs.set('live', { id: 'live', status: 'running' })
    assert('未结束的行不能删记录', liveUploads.forget('live') === false)

    // A failed download has no file left to delete.
    const failedDownloads = new DownloadQueue({
      registry: {},
      cloud: {},
      localLibrary: {},
      history: null,
    })
    failedDownloads.restore([downloadRecord({ id: 'fail', status: 'failed', outPath: '', error: 'x' })])
    assert('失败的下载没有可删文件', (await failedDownloads.deleteFile('fail')) === false)
    assert('但记录仍然能删', failedDownloads.forget('fail') === true)
  }

  // ── 9. Restored rows are not re-queued ──
  {
    const uploads = new UploadQueue({ cloud: {}, history: null })
    uploads.restore([uploadRecord({ id: 'old', status: 'failed', error: 'gone' })])
    assert('恢复的行不进入待办队列', uploads.pending.length === 0)
    assert('恢复的行可以直接取消而不报错', uploads.cancel('old') === false)
  }
} finally {
  await fs.rm(tempDir, { recursive: true, force: true })
}

console.log(`\n${checks - failures}/${checks} 项通过`)
process.exit(failures ? 1 : 0)
