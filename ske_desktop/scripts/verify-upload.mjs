/**
 * Upload pipeline test against a mock AList server.
 *
 * Usage:
 *   node scripts/verify-upload.mjs
 *
 * The mock speaks `PUT /api/fs/put` and the `/api/task/upload/*` endpoints the
 * way AList does: with `As-Task: true` the storage write becomes a task and the
 * response comes back with its id, and that task's `progress` is the number the
 * desktop bar shows as the solid blue second half. No mock can prove what a
 * real server does with a real driver, but this pins down what we send, what we
 * read back, and how the two halves are reported.
 */
import { getEventListeners } from 'node:events'
import fs from 'node:fs/promises'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { AListClient } from '../src/main/alist.js'
import { delay } from '../src/main/remote-client.js'
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

const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'ske-verify-upload-'))
const filePath = path.join(tempDir, 'sample.ske')

// Odd primes keep the bytes non-uniform: a truncated or reordered body shows up
// as a mismatch rather than as an equally plausible run of zeros.
const payload = Buffer.alloc(512 * 1024)
for (let i = 0; i < payload.length; i += 1) payload[i] = (i * 7) % 251
await fs.writeFile(filePath, payload)

/** A mock that behaves like AList for one upload, and records everything. */
function createMock({
  asTask = true,
  taskApi = true,
  progressSteps = [0, 50, 100],
  respondDelayMs = 0,
  resetWhen = '',
  rejectStatus = 0,
} = {}) {
  const state = {
    putHeaders: null,
    putMethod: '',
    received: Buffer.alloc(0),
    formBody: 0,
    formType: '',
    polls: 0,
    deletes: [],
    cancels: [],
    resets: 0,
    attempts: 0,
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1')
    const json = (code, data, status = 200) => {
      res.writeHead(status, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ code, message: code === 200 ? 'success' : 'nope', data }))
    }

    if (url.pathname === '/api/fs/put') {
      state.putMethod = req.method
      state.putHeaders = { ...req.headers }
      state.attempts += 1

      if (rejectStatus) {
        // What Nginx does with a body over client_max_body_size: answer, never
        // read the rest, then drop the connection. The answer is HTML, so the
        // client cannot parse a code out of it.
        res.writeHead(rejectStatus, {
          'Content-Type': 'text/html',
          Server: 'nginx/1.24.0',
        })
        res.end('<html><head><title>413 Request Entity Too Large</title></head></html>')
        setTimeout(() => req.destroy(), 10)
        return
      }

      // A server that answers by hanging up — what a client sees as
      // `read ECONNRESET` while it is still pushing the body.
      if (resetWhen === 'put' || (resetWhen === 'task' && req.headers['as-task'] === 'true')) {
        state.resets += 1
        req.destroy()
        return
      }

      // Drain slowly: that backpressure is what makes the client's own byte
      // counter advance instead of jumping to the full size in one tick.
      const chunks = []
      for await (const chunk of req) {
        chunks.push(chunk)
        await wait(2)
      }
      state.received = Buffer.concat(chunks)
      if (respondDelayMs) await wait(respondDelayMs)
      // A task only exists when it was asked for; without the header the write
      // is synchronous and the answer means "already in the storage backend".
      const wantsTask = asTask && req.headers['as-task'] === 'true'
      json(200, wantsTask ? { task: { id: 't-1', state: 'running', progress: 0, end_time: null } } : null)
      return
    }

    if (url.pathname === '/api/fs/form') {
      state.formType = String(req.headers['content-type'] || '')
      for await (const chunk of req) state.formBody += chunk.length
      json(200, null)
      return
    }

    if (url.pathname === '/api/task/upload/info') {
      state.polls += 1
      if (!taskApi) {
        res.writeHead(404)
        res.end()
        return
      }
      const progress = progressSteps[Math.min(state.polls - 1, progressSteps.length - 1)]
      const done = progress >= 100
      json(200, [
        {
          id: url.searchParams.get('tid'),
          state: done ? 'succeeded' : 'running',
          progress,
          end_time: done ? '2026-01-01T00:00:00Z' : null,
          error: '',
        },
      ])
      return
    }

    if (url.pathname === '/api/task/upload/delete') {
      state.deletes.push(url.searchParams.get('tid'))
      json(200, null)
      return
    }

    if (url.pathname === '/api/task/upload/cancel') {
      state.cancels.push(url.searchParams.get('tid'))
      json(200, null)
      return
    }

    res.writeHead(404)
    res.end()
  })

  return { server, state }
}

async function listen(server) {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  return `http://127.0.0.1:${server.address().port}`
}

const servers = []
async function withMock(options) {
  const mock = createMock(options)
  servers.push(mock.server)
  return { ...mock, base: await listen(mock.server) }
}

try {
  // ── 1. Two-phase upload: body counted, then the server's task polled ──
  {
    const { server, state, base } = await withMock({})
    const client = new AListClient({ server: base, token: 'mock-token' })
    const reports = []
    const result = await client.upload('/电影/我的 视频.ske', filePath, {
      onProgress: (p) => reports.push(p),
    })

    assert('走的是 PUT /api/fs/put', state.putMethod === 'PUT', state.putMethod)
    assert('带上 As-Task', state.putHeaders?.['as-task'] === 'true', String(state.putHeaders?.['as-task']))
    assert(
      'File-Path 是完整目标路径',
      decodeURI(String(state.putHeaders?.['file-path'])) === '/电影/我的 视频.ske',
      String(state.putHeaders?.['file-path']),
    )
    assert(
      'Content-Length 等于容器大小',
      String(state.putHeaders?.['content-length']) === String(payload.length),
      String(state.putHeaders?.['content-length']),
    )
    assert('正文逐字节一致', state.received.equals(payload), `${state.received.length} B`)

    const uploads = reports.filter((p) => p.phase === 'uploading')
    const sent = uploads.map((p) => p.sent)
    assert('上传阶段报告了多个不同字节数', new Set(sent).size >= 2, `distinct=${new Set(sent).size}`)
    assert('已发送字节单调递增', sent.every((v, i) => i === 0 || v >= sent[i - 1]))
    assert('最终已发送 = 容器大小', sent.at(-1) === payload.length, String(sent.at(-1)))

    const storing = reports.filter((p) => p.phase === 'storing')
    assert('进入了写入云端阶段', storing.length > 0)
    assert('上传阶段不知道云端进度', uploads.every((p) => p.stored === null))
    const stored = storing.map((p) => p.stored).filter((v) => v !== null)
    assert('云端进度单调递增', stored.every((v, i) => i === 0 || v >= stored[i - 1]), stored.join(','))
    assert('云端进度最终写满', stored.at(-1) === payload.length, String(stored.at(-1)))
    assert('返回值两段都是满的', result?.sent === payload.length && result?.stored === payload.length)

    await wait(120)
    assert('完成的任务被删除', state.deletes.includes('t-1'), state.deletes.join(','))

    server.close()
  }

  // ── 2. Server ignores As-Task: synchronous write, no task to poll ──
  {
    const { server, state, base } = await withMock({ asTask: false })
    const client = new AListClient({ server: base, token: 'mock-token' })
    const reports = []
    await client.upload('/a.ske', filePath, { onProgress: (p) => reports.push(p) })

    assert('同步写入不再轮询任务', state.polls === 0, `polls=${state.polls}`)
    assert(
      '同步写入直接标记两段完成',
      reports.at(-1)?.sent === payload.length && reports.at(-1)?.stored === payload.length,
    )
    server.close()
  }

  // ── 3. Task endpoint missing: upload still succeeds, cloud number unknown ──
  {
    const { server, state, base } = await withMock({ taskApi: false })
    const client = new AListClient({ server: base, token: 'mock-token' })
    let failed = null
    let result = null
    try {
      result = await client.upload('/b.ske', filePath)
    } catch (err) {
      failed = err
    }
    assert('读不到任务不报错', !failed, failed?.message)
    assert('上传结果仍算成功', result?.sent === payload.length)
    assert('确实尝试过任务接口', state.polls > 0, `polls=${state.polls}`)
    server.close()
  }

  // ── 4. Cancel while the cloud write is running ──
  {
    const { server, state, base } = await withMock({ progressSteps: [10, 20, 100] })
    const client = new AListClient({ server: base, token: 'mock-token' })
    const controller = new AbortController()
    let failed = null

    try {
      await client.upload('/c.ske', filePath, {
        signal: controller.signal,
        onProgress: (p) => {
          if (p.phase === 'storing' && p.stored !== null) controller.abort()
        },
      })
    } catch (err) {
      failed = err
    }

    assert('取消后上传被中断', !!failed)
    await wait(120)
    assert('取消传到了服务器任务', state.cancels.includes('t-1'), state.cancels.join(',') || 'none')
    server.close()
  }

  // ── 5. Server hangs up on As-Task: retry the same file without it ──
  {
    const { server, state, base } = await withMock({ resetWhen: 'task' })
    const client = new AListClient({ server: base, token: 'mock-token' })
    const reports = []
    const result = await client.upload('/e.ske', filePath, {
      onProgress: (p) => reports.push(p),
    })

    assert('连接中断后没有直接失败', result?.sent === payload.length, JSON.stringify(result))
    assert('重试时不再带 As-Task', state.putHeaders?.['as-task'] === undefined, String(state.putHeaders?.['as-task']))
    assert('正文仍然是完整的', state.received.equals(payload), `${state.received.length} B`)
    assert('降级到同步流式上传', client.uploadMode === 'stream', client.uploadMode)
    assert('降级后计数从头开始', reports.some((p) => p.restart === true))
    server.close()
  }

  // ── 6. Proxy answers 413: fail once, with an explanation ──
  {
    const { server, state, base } = await withMock({ rejectStatus: 413 })
    const client = new AListClient({ server: base, token: 'mock-token' })
    let failed = null

    try {
      await client.upload('/big.ske', filePath, { onProgress: () => {} })
    } catch (err) {
      failed = err
    }

    assert('413 会失败', !!failed)
    assert('413 只传一次', state.attempts === 1, `attempts=${state.attempts}`)
    assert('413 不降级重试', client.uploadMode === 'task', client.uploadMode)
    assert('413 的提示说明是反代限制', /413/.test(failed?.message || ''), failed?.message)
    assert('413 的提示带文件大小', /512\.0 KB|KB/.test(failed?.message || ''), failed?.message)
    assert('413 的提示指出是谁拦的', /nginx/.test(failed?.message || ''), failed?.message)
    // A missing message key is returned verbatim, so a leaked `errors.` prefix
    // in user-facing text always means the table and the code disagree.
    assert('错误信息里不漏 key', !/errors\./.test(failed?.message || ''), failed?.message)
    server.close()
  }

  // ── 7. Raw endpoint missing: fall back to the form upload, once ──
  {
    const { server, state, base } = await withMock({})
    // Replace the handler set with one that has no /api/fs/put at all.
    server.removeAllListeners('request')
    server.on('request', async (req, res) => {
      const url = new URL(req.url, 'http://127.0.0.1')
      if (url.pathname === '/api/fs/form') {
        state.formType = String(req.headers['content-type'] || '')
        for await (const chunk of req) state.formBody += chunk.length
        res.writeHead(200, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify({ code: 200, message: 'success', data: null }))
        return
      }
      res.writeHead(404)
      res.end()
    })

    const client = new AListClient({ server: base, token: 'mock-token' })
    const result = await client.upload('/d.ske', filePath, { onProgress: () => {} })

    assert('退回表单上传成功', result?.stored === payload.length, JSON.stringify(result))
    assert('表单是 multipart', state.formType.startsWith('multipart/form-data'), state.formType)
    assert('表单带上了正文', state.formBody > payload.length, `${state.formBody} B`)
    assert('记住了该服务器只认表单', client.uploadMode === 'form', client.uploadMode)
    server.close()
  }

  // ── 7. Queue snapshot: the exact fields the transfers page reads ──
  {
    const { server, base } = await withMock({ progressSteps: [0, 60, 100], respondDelayMs: 400 })
    const client = new AListClient({ server: base, token: 'mock-token' })
    const snapshots = []

    const queue = new UploadQueue({
      // Only what the queue actually asks of the cloud service.
      cloud: {
        uploadTarget: () => ({ client, dir: '/d', password: '', nameKey: null }),
      },
      onUpdate: (jobs) => snapshots.push({ ...jobs[0] }),
    })

    queue.add({
      sessionId: 's',
      path: '/',
      files: [{ path: filePath, name: 'sample.ske' }],
      encrypt: false,
    })

    for (let i = 0; i < 100; i += 1) {
      if (queue.snapshot()[0]?.status === 'done') break
      await wait(100)
    }

    const phases = new Set(snapshots.map((s) => s.phase))
    assert('队列经过上传阶段', phases.has('uploading'), [...phases].join(','))
    assert('队列经过写入云端阶段', phases.has('storing'), [...phases].join(','))

    const midway = snapshots.filter((s) => s.phase === 'storing' && s.storedBytes !== null)
    assert('写入云端阶段带着云端字节数', midway.length > 0)
    assert(
      '云端字节数不超过容器大小',
      midway.every((s) => s.storedBytes <= s.uploadBytes),
      midway.map((s) => s.storedBytes).join(','),
    )

    const last = queue.snapshot()[0]
    assert('任务完成', last?.status === 'done', last?.status)
    assert('容器大小已上报', last?.uploadBytes === payload.length, String(last?.uploadBytes))
    assert(
      '两段计数最终相等',
      last?.sentBytes === payload.length && last?.storedBytes === payload.length,
      `${last?.sentBytes}/${last?.storedBytes}`,
    )
    server.close()
  }

  // ── Waiting on a signal must not accumulate listeners ──
  {
    const controller = new AbortController()
    // The task poll loop wakes up on this one signal for the whole storage
    // write; one leftover listener per tick is what produced the
    // MaxListenersExceededWarning after eleven of them.
    for (let i = 0; i < 25; i += 1) await delay(1, controller.signal)
    const leaked = getEventListeners(controller.signal, 'abort').length
    assert('等待结束不留下 abort 监听', leaked === 0, `listeners=${leaked}`)

    const pending = delay(1000, controller.signal)
    controller.abort()
    const aborted = await pending.then(() => null, (err) => err)
    assert('取消会中断等待', aborted instanceof Error, String(aborted))
    const afterAbort = getEventListeners(controller.signal, 'abort').length
    assert('中断后也没有残留监听', afterAbort === 0, `listeners=${afterAbort}`)
  }
} finally {
  for (const server of servers) server.close()
  await fs.rm(tempDir, { recursive: true, force: true })
}

console.log(`\n${checks - failures}/${checks} 项通过`)
process.exit(failures ? 1 : 0)
