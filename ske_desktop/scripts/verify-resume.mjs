/**
 * Resuming uploads that outlive the app.
 *
 * Usage:
 *   node scripts/verify-resume.mjs
 *
 * What this covers: the on-disk row the queue writes while an upload is in
 * flight, and the path the next launch takes — log back in, claim the server's
 * task id, and either watch it finish or learn that it is already done. The
 * mock speaks `fs/put` with `As-Task` and `/api/task/upload/*` the way AList
 * does, so the client code runs unmodified.
 *
 * Not covered: `index.js` itself (it needs Electron). The wiring there is just
 * "load the file, skip rows without a task id, adopt the rest" — the last two
 * are exercised here explicitly.
 */
import fs from 'node:fs/promises'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { AListClient } from '../src/main/alist.js'
import { PendingUploads } from '../src/main/pending-uploads.js'
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

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'ske-verify-resume-'))
const filePath = path.join(tempDir, 'payload.ske')
const payload = Buffer.alloc(128 * 1024)
for (let i = 0; i < payload.length; i += 1) payload[i] = (i * 11) % 251
await fs.writeFile(filePath, payload)

/**
 * A mock AList: `fs/put` answered with a task, `/api/task/upload/*` scripted.
 *
 * `progressSteps` is read one poll at a time; an empty list means the task
 * never shows up in the answers, which is what a restart discovers when the
 * server was restarted too.
 */
function createMock({ progressSteps = [0, 100], knownTask = true } = {}) {
  const state = { polls: 0, deletes: [], cancels: [] }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1')
    const json = (data, status = 200) => {
      res.writeHead(status, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ code: status === 200 ? 200 : status, message: 'ok', data }))
    }

    if (url.pathname === '/api/fs/put') {
      for await (const chunk of req) void chunk
      json({ task: { id: 't-upload', state: 'running', progress: 0, end_time: null } })
      return
    }

    if (url.pathname === '/api/task/upload/info') {
      req.resume()
      state.polls += 1
      const tid = url.searchParams.get('tid')
      if (!knownTask) {
        json([])
        return
      }
      const progress = progressSteps[Math.min(state.polls - 1, progressSteps.length - 1)]
      const done = progress >= 100
      json([
        {
          id: tid,
          state: done ? 'succeeded' : 'running',
          progress,
          end_time: done ? '2026-01-01T00:00:00Z' : null,
          error: '',
        },
      ])
      return
    }

    if (url.pathname === '/api/task/upload/delete') {
      req.resume()
      state.deletes.push(url.searchParams.get('tid'))
      json(null)
      return
    }

    if (url.pathname === '/api/task/upload/cancel') {
      req.resume()
      state.cancels.push(url.searchParams.get('tid'))
      json(null)
      return
    }

    res.writeHead(404)
    res.end()
  })

  return { server, state }
}

const servers = []
async function withMock(options) {
  const mock = createMock(options)
  servers.push(mock.server)
  await new Promise((resolve) => mock.server.listen(0, '127.0.0.1', resolve))
  return { ...mock, base: `http://127.0.0.1:${mock.server.address().port}` }
}

/** What `index.js` builds around an upload target, minus the session lookup. */
function queueFor({ client, pending, history, payloadDir = path.join(tempDir, 'payloads') }) {
  return new UploadQueue({
    cloud: {
      uploadTarget: () => ({
        client,
        dir: '/d',
        password: '',
        nameKey: null,
        profileId: 'p1',
      }),
    },
    history,
    pending,
    tempDir: path.join(tempDir, 'stage'),
    payloadDir,
  })
}

/** Stands in for OpenList: has the chunked endpoints and takes whole files. */
function chunkClient() {
  const calls = []
  return {
    supportsMultipartUpload: true,
    calls,
    async upload(absPath, filePath, { onProgress } = {}) {
      const size = (await fs.stat(filePath)).size
      onProgress?.({ sent: 0, stored: null, phase: 'uploading' })
      onProgress?.({ sent: size, stored: null, phase: 'uploading' })
      calls.push({ absPath, filePath, size })
    },
  }
}

function pendingEntry(overrides = {}) {
  return {
    id: 'job-1',
    profileId: 'p1',
    dir: '/电影',
    name: 'movie.mp4',
    encrypt: true,
    taskId: 't-restored',
    phase: 'storing',
    totalBytes: 2048,
    uploadBytes: 2100,
    sentBytes: 2100,
    startedAt: 1000,
    updatedAt: 2000,
    ...overrides,
  }
}

try {
  // ── 1. A live upload writes its task id, then clears the row ──
  {
    const { server, base } = await withMock({ progressSteps: [0, 100] })
    const pending = new PendingUploads({ filePath: path.join(tempDir, 'live.json') })
    const history = new TransferHistory({ filePath: path.join(tempDir, 'live-history.json') })
    await pending.load()
    await history.load()

    const client = new AListClient({ server: base, token: 'mock-token' })
    const queue = queueFor({ client, pending, history })

    let sawTaskRow = false
    const originalUpsert = pending.upsert.bind(pending)
    pending.upsert = (entry) => {
      if (entry.taskId) sawTaskRow = true
      originalUpsert(entry)
    }

    queue.add({ sessionId: 's1', path: '/', files: [{ path: filePath, name: 'sample.ske' }], encrypt: false })
    for (let i = 0; i < 60; i += 1) {
      if (queue.snapshot()[0]?.status === 'done') break
      await wait(100)
    }

    assert('上传过程中记下了任务号', sawTaskRow)
    assert('结束的行已从待续表里移除', pending.entries().length === 0, `count=${pending.entries().length}`)
    assert('历史里有这条记录', history.recordsFor('upload').length === 1)
    server.close()
  }

  // ── 2. Restart: the task is still running, so the queue watches it finish ──
  {
    const { server, state, base } = await withMock({ progressSteps: [0, 40, 100] })
    const pending = new PendingUploads({ filePath: path.join(tempDir, 'resume.json') })
    const history = new TransferHistory({ filePath: path.join(tempDir, 'resume-history.json') })
    await pending.load()
    await history.load()
    pending.upsert(pendingEntry())
    pending.flush()

    const reopened = new PendingUploads({ filePath: path.join(tempDir, 'resume.json') })
    await reopened.load()
    const entries = reopened.entries()
    assert('重启后读得到待续记录', entries.length === 1 && entries[0].taskId === 't-restored')

    const client = new AListClient({ server: base, token: 'mock-token' })
    const queue = queueFor({ client, pending: reopened, history })

    const { job, signal } = queue.adopt(entries[0])
    assert('接管的行立刻出现在队列里', queue.snapshot()[0]?.status === 'running')
    assert('接管时还不知道云端进度', queue.snapshot()[0]?.storedBytes === null)

    const seen = []
    await client.waitForUploadTask(entries[0].taskId, {
      size: job.uploadBytes,
      signal,
      onProgress: (progress) => {
        if (Number.isFinite(progress?.stored)) {
          job.storedBytes = Math.min(job.uploadBytes, progress.stored)
          queue.touch()
          seen.push(job.storedBytes)
        }
      },
    })
    queue.finishRestored(job, 'done')

    assert('轮询到了云端进度', seen.length >= 2 && seen.at(-1) === job.uploadBytes, seen.join(','))
    assert('接管后状态是完成', queue.snapshot()[0]?.status === 'done')
    assert('待续表已清空', reopened.entries().length === 0)
    assert('历史里补上了这条', history.recordsFor('upload')[0]?.status === 'done')
    await wait(120)
    assert('完成的任务被清掉', state.deletes.includes('t-restored'), state.deletes.join(','))
    server.close()
  }

  // ── 3. Restart: the task is gone — say so instead of claiming success ──
  {
    const { server, base } = await withMock({ knownTask: false })
    const pending = new PendingUploads({ filePath: path.join(tempDir, 'gone.json') })
    const history = new TransferHistory({ filePath: path.join(tempDir, 'gone-history.json') })
    await pending.load()
    await history.load()
    pending.upsert(pendingEntry({ id: 'job-2', taskId: 't-gone' }))

    const client = new AListClient({ server: base, token: 'mock-token' })
    const queue = queueFor({ client, pending, history })
    const { job, signal } = queue.adopt(pending.entries()[0])

    let failed = null
    try {
      await client.waitForUploadTask('t-gone', { size: job.uploadBytes, signal })
    } catch (err) {
      failed = err
    }
    queue.finishRestored(job, 'failed', failed?.message || '')

    assert('任务消失算失败', !!failed)
    assert(
      '提示让用户去目录确认',
      /确认|check/i.test(failed?.message || ''),
      failed?.message,
    )
    const record = history.recordsFor('upload')[0]
    assert('历史里记成失败', record?.status === 'failed')
    assert('待续表也清空了', pending.entries().length === 0)
    server.close()
  }

  // ── 3b. A chunked upload keeps its container, so a restart has something to send ──
  {
    const bigPath = path.join(tempDir, 'big.bin')
    await fs.writeFile(bigPath, '')
    await fs.truncate(bigPath, 32 * 1024 * 1024 + 1024)

    const pending = new PendingUploads({ filePath: path.join(tempDir, 'chunk-live.json') })
    const history = new TransferHistory({ filePath: path.join(tempDir, 'chunk-live-history.json') })
    await pending.load()
    await history.load()

    const rows = []
    const originalUpsert = pending.upsert.bind(pending)
    pending.upsert = (entry) => {
      rows.push(entry)
      originalUpsert(entry)
    }

    const client = chunkClient()
    const queue = queueFor({ client, pending, history })
    queue.add({ sessionId: 's1', path: '/', files: [{ path: bigPath, name: 'big.bin' }], encrypt: false })
    for (let i = 0; i < 60; i += 1) {
      if (queue.snapshot()[0]?.status === 'done') break
      await wait(100)
    }

    const chunkRow = rows.find((row) => row.mode === 'chunk')
    assert('分片上传会写 chunk 行', !!chunkRow, rows.map((row) => row.mode).join(','))
    assert('chunk 行带远端名', chunkRow?.remoteName === 'big.bin', chunkRow?.remoteName)
    assert('chunk 行带保留载荷', /\.payload$/.test(chunkRow?.payloadPath || ''), chunkRow?.payloadPath)
    assert('上传读的就是保留的那份载荷', client.calls[0]?.filePath === chunkRow?.payloadPath, client.calls[0]?.filePath)
    assert('结束后载荷被删掉', !(await fs.stat(chunkRow.payloadPath).catch(() => null)))
    assert('结束后待续行也清掉', pending.entries().length === 0)
  }

  // ── 3c. Restart: the kept container is re-sent, the row closes out ──
  {
    const payloadDir = path.join(tempDir, 'payloads')
    await fs.mkdir(payloadDir, { recursive: true })
    const payloadPath = path.join(payloadDir, 'job-chunk.payload')
    const chunkBytes = Buffer.alloc(96 * 1024, 7)
    await fs.writeFile(payloadPath, chunkBytes)

    const pending = new PendingUploads({ filePath: path.join(tempDir, 'chunk-resume.json') })
    const history = new TransferHistory({ filePath: path.join(tempDir, 'chunk-resume-history.json') })
    await pending.load()
    await history.load()
    const entry = pendingEntry({
      id: 'job-chunk',
      mode: 'chunk',
      taskId: '',
      remoteName: 'movie.ske',
      payloadPath,
      uploadBytes: chunkBytes.length,
      totalBytes: chunkBytes.length,
      sentBytes: 0,
    })
    pending.upsert(entry)

    const client = chunkClient()
    const queue = queueFor({ client, pending, history, payloadDir })

    const { job, signal } = queue.adoptChunk(pending.entries()[0])
    assert('接管的 chunk 行立刻出现在队列里', queue.snapshot()[0]?.status === 'running')
    assert('接管时进度从零开始', queue.snapshot()[0]?.sentBytes === 0)

    await queue.resumeChunk(job, client, { signal })

    assert('沿用了原来的远端路径', client.calls[0]?.absPath === '/电影/movie.ske', client.calls[0]?.absPath)
    assert('发送的是保留下来的容器', client.calls[0]?.size === chunkBytes.length)
    assert('续传完成后状态是完成', queue.snapshot()[0]?.status === 'done')
    assert('完成后待续行被清掉', pending.entries().length === 0)
    assert('完成后载荷被删掉', !(await fs.stat(payloadPath).catch(() => null)))
    assert('历史里补上了这条', history.recordsFor('upload')[0]?.status === 'done')
  }

  // ── 3d. A chunk row whose container vanished is reported, not resumed ──
  {
    const pending = new PendingUploads({ filePath: path.join(tempDir, 'chunk-gone.json') })
    const history = new TransferHistory({ filePath: path.join(tempDir, 'chunk-gone-history.json') })
    await pending.load()
    await history.load()
    const entry = pendingEntry({
      id: 'job-missing',
      mode: 'chunk',
      taskId: '',
      remoteName: 'movie.ske',
      payloadPath: path.join(tempDir, 'gone.payload'),
    })
    pending.upsert(entry)

    const queue = queueFor({ client: chunkClient(), pending, history })
    // `resumePendingUploads` checks the file and calls this instead of adopting.
    queue.failPending(entry, 'kept file is gone')

    const row = queue.snapshot()[0]
    assert('载荷丢失算失败', row?.status === 'failed', row?.status)
    assert('失败提示写清了原因', /gone/.test(row?.error || ''), row?.error)
    assert('失败的行不再待续', pending.entries().length === 0)
    assert('历史里记成失败', history.recordsFor('upload')[0]?.status === 'failed')
  }

  // ── 3e. Containers no row points at are swept on launch ──
  {
    const payloadDir = path.join(tempDir, 'sweep')
    await fs.mkdir(payloadDir, { recursive: true })
    const keptPath = path.join(payloadDir, 'kept.payload')
    const orphanPath = path.join(payloadDir, 'orphan.payload')
    await fs.writeFile(keptPath, 'kept')
    await fs.writeFile(orphanPath, 'orphan')

    const pending = new PendingUploads({ filePath: path.join(tempDir, 'sweep.json') })
    await pending.load()
    pending.upsert(
      pendingEntry({
        id: 'kept',
        mode: 'chunk',
        taskId: '',
        payloadPath: keptPath,
      }),
    )

    const queue = queueFor({ client: chunkClient(), pending, history: null, payloadDir })
    const removed = await queue.sweepPayloads()

    assert('只清掉没人指向的载荷', removed === 1, `removed=${removed}`)
    assert('待续行指向的载荷还在', Boolean(await fs.stat(keptPath).catch(() => null)))
    assert('孤儿载荷被删除', !(await fs.stat(orphanPath).catch(() => null)))
    assert('待续行没被误删', pending.entries().length === 1)
  }

  // ── 4. A row with no task id is not recoverable ──
  {
    const pending = new PendingUploads({ filePath: path.join(tempDir, 'notask.json') })
    await pending.load()
    pending.upsert(pendingEntry({ id: 'job-3', taskId: '' }))
    pending.upsert(pendingEntry({ id: 'job-4', taskId: 't-ok' }))

    // The same filter `resumePendingUploads` applies.
    const recoverable = pending.entries().filter((entry) => entry.taskId)
    assert('没有任务号的行被跳过', recoverable.length === 1 && recoverable[0].id === 'job-4')
  }

  // ── 5. File handling: replace, remove, reload, and a corrupt file ──
  {
    const file = path.join(tempDir, 'file.json')
    const pending = new PendingUploads({ filePath: file })
    await pending.load()

    pending.upsert(pendingEntry())
    pending.upsert(pendingEntry({ sentBytes: 10 }))
    assert('同一任务只留一行', pending.entries().length === 1, `count=${pending.entries().length}`)
    assert('保留的是最新状态', pending.entries()[0].sentBytes === 10)

    pending.flush()
    const reopened = new PendingUploads({ filePath: file })
    await reopened.load()
    assert('落盘后能读回', reopened.entries()[0]?.id === 'job-1')

    reopened.remove('job-1')
    reopened.flush()
    const afterRemove = new PendingUploads({ filePath: file })
    await afterRemove.load()
    assert('移除也会落盘', afterRemove.entries().length === 0)

    const corrupt = path.join(tempDir, 'corrupt.json')
    await fs.writeFile(corrupt, 'nonsense')
    const broken = new PendingUploads({ filePath: corrupt })
    await broken.load()
    assert('损坏的文件不会拖垮启动', broken.entries().length === 0)
  }
} finally {
  for (const server of servers) server.close()
  await fs.rm(tempDir, { recursive: true, force: true })
}

console.log(`\n${checks - failures}/${checks} 项通过`)
process.exit(failures ? 1 : 0)
