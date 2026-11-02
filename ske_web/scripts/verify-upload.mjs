/**
 * Web upload pipeline test: byte progress, the server's upload task, and the
 * errors that come back instead.
 *
 * Usage:
 *   node scripts/verify-upload.mjs
 *
 * The page uses `XMLHttpRequest` for uploads because it is the only browser API
 * that reports how much of a request body has gone out. Node has no XHR, so the
 * class below forwards the real multipart body over `node:http` and drives the
 * same callbacks — which makes the module under test run unmodified.
 */
import fs from 'node:fs/promises'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { uploadFile } from '../src/composables/remoteClient.js'

let checks = 0
let failures = 0
function assert(label, condition, detail = '') {
  checks += 1
  if (condition) return
  failures += 1
  console.error(`FAIL ${label}${detail ? ': ' + detail : ''}`)
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** Set for one request to simulate a CORS preflight that never reaches AList. */
let blockNextRequest = false

class FakeXHR {
  constructor() {
    this.upload = {}
    this.requestHeaders = {}
    this.method = 'GET'
    this.url = ''
    this.status = 0
    this.responseText = ''
  }

  open(method, url) {
    this.method = method
    this.url = url
  }

  setRequestHeader(name, value) {
    this.requestHeaders[name] = value
  }

  send(body) {
    if (blockNextRequest) {
      blockNextRequest = false
      // A refused preflight: no status, and not a byte on the wire.
      setTimeout(() => this.onerror?.(), 0)
      return
    }
    forward(this, body).catch(() => this.onerror?.())
  }
}
globalThis.XMLHttpRequest = FakeXHR

/**
 * Send the FormData the way a browser would, reporting progress per chunk.
 *
 * `new Response(formData)` serialises the multipart envelope exactly like the
 * browser does, so the server receives the real thing rather than a stub.
 */
async function forward(xhr, formData) {
  const prepared = new Response(formData)
  const payload = Buffer.from(await prepared.arrayBuffer())
  const url = new URL(xhr.url)
  const transport = url.protocol === 'https:' ? https : http
  const headers = {
    ...xhr.requestHeaders,
    'Content-Type': prepared.headers.get('content-type') || '',
    'Content-Length': String(payload.length),
  }

  await new Promise((resolve, reject) => {
    const req = transport.request(url, { method: xhr.method, headers })

    req.on('response', (res) => {
      let text = ''
      res.setEncoding('utf8')
      res.on('data', (chunk) => {
        text += chunk
      })
      res.on('end', () => {
        xhr.status = res.statusCode
        xhr.responseText = text
        xhr.onload?.()
        resolve()
      })
    })
    req.on('error', reject)

    const CHUNK = 16 * 1024
    let offset = 0
    const pump = () => {
      if (offset >= payload.length) {
        req.end()
        return
      }
      const end = Math.min(payload.length, offset + CHUNK)
      const chunk = payload.subarray(offset, end)
      offset = end
      req.write(chunk, () => {
        xhr.upload.onprogress?.({ lengthComputable: true, loaded: offset, total: payload.length })
        setImmediate(pump)
      })
    }
    pump()
  })
}

const payload = Buffer.alloc(96 * 1024)
for (let i = 0; i < payload.length; i += 1) payload[i] = (i * 13) % 251

function createMock({ task = true, taskApi = true, progressSteps = [0, 50, 100], rejectStatus = 0 } = {}) {
  const state = { headers: null, body: Buffer.alloc(0), attempts: 0, polls: 0, deletes: [] }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1')
    const json = (data, status = 200) => {
      res.writeHead(status, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ code: status === 200 ? 200 : status, message: 'ok', data }))
    }

    if (url.pathname === '/api/fs/form') {
      state.attempts += 1
      state.headers = { ...req.headers }
      const chunks = []
      for await (const chunk of req) chunks.push(chunk)
      state.body = Buffer.concat(chunks)

      if (rejectStatus) {
        res.writeHead(rejectStatus, { 'Content-Type': 'text/html', Server: 'nginx/1.24.0' })
        res.end('<html><head><title>413 Request Entity Too Large</title></head></html>')
        return
      }
      // A task only exists when it was asked for.
      const wantsTask = task && req.headers['as-task'] === 'true'
      json(wantsTask ? { task: { id: 't-1', state: 'running', progress: 0, end_time: null } } : null)
      return
    }

    if (url.pathname === '/api/task/upload/info') {
      req.resume()
      state.polls += 1
      if (!taskApi) {
        res.writeHead(404)
        res.end()
        return
      }
      const progress = progressSteps[Math.min(state.polls - 1, progressSteps.length - 1)]
      const done = progress >= 100
      json([
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
      req.resume()
      state.deletes.push(url.searchParams.get('tid'))
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

try {
  // ── 1. Byte progress, then the server's own task ──
  {
    const { server, state, base } = await withMock({})
    const reports = []
    const file = new File([payload], 'a.ske', { type: 'application/octet-stream' })
    const data = await uploadFile({ server: base, token: 'mock-token' }, '/目录/a.ske', file, {
      onProgress: (progress) => reports.push(progress),
    })

    assert('带上 As-Task', state.headers?.['as-task'] === 'true', String(state.headers?.['as-task']))
    assert(
      'File-Path 是完整目标路径',
      decodeURI(String(state.headers?.['file-path'])) === '/目录/a.ske',
      String(state.headers?.['file-path']),
    )
    assert('正文完整送达', state.body.includes(payload.subarray(0, 4096)), `${state.body.length} B`)

    const uploading = reports.filter((p) => p.phase === 'uploading')
    const sent = uploading.map((p) => p.sent)
    assert('上传阶段报告了多个进度点', new Set(sent).size >= 2, `distinct=${new Set(sent).size}`)
    assert('已发送比例单调', sent.every((v, i) => i === 0 || v >= sent[i - 1]))
    assert('上传阶段不知道云端进度', uploading.every((p) => p.stored === null))

    const stored = reports.filter((p) => p.phase === 'storing').map((p) => p.stored)
    assert('进入写入云端阶段', stored.length > 0)
    assert('云端进度最终写满', stored.at(-1) === 1, stored.join(','))
    assert('返回值带着任务信息', data?.task?.id === 't-1', JSON.stringify(data))

    await wait(120)
    assert('完成的任务被清掉', state.deletes.includes('t-1'), state.deletes.join(','))
    server.close()
  }

  // ── 2. Synchronous write: no task, both halves complete at once ──
  {
    const { server, state, base } = await withMock({ task: false })
    const reports = []
    const file = new File([payload], 'b.ske')
    await uploadFile({ server: base, token: 'mock-token' }, '/b.ske', file, {
      onProgress: (progress) => reports.push(progress),
    })

    assert('同步写入不轮询任务', state.polls === 0, `polls=${state.polls}`)
    assert(
      '同步写入直接标记两段完成',
      reports.at(-1)?.sent === 1 && reports.at(-1)?.stored === 1,
      JSON.stringify(reports.at(-1)),
    )
    server.close()
  }

  // ── 3. Proxy answers 413: one attempt, an explanation ──
  {
    const { server, state, base } = await withMock({ rejectStatus: 413 })
    let failed = null
    try {
      await uploadFile({ server: base, token: 'mock-token' }, '/big.ske', new File([payload], 'big.ske'), {})
    } catch (err) {
      failed = err
    }

    assert('413 会失败', !!failed)
    assert('413 只请求一次', state.attempts === 1, `attempts=${state.attempts}`)
    assert('413 的提示提到上限', /413/.test(failed?.message || ''), failed?.message)
    assert('413 的提示带文件大小', /KB|MB/.test(failed?.message || ''), failed?.message)
    assert('提示里不漏 key', !/errors\./.test(failed?.message || ''), failed?.message)
    server.close()
  }

  // ── 4. Refused preflight: retry without the optional header ──
  {
    const { server, state, base } = await withMock({})
    blockNextRequest = true
    const reports = []
    await uploadFile({ server: base, token: 'mock-token' }, '/c.ske', new File([payload], 'c.ske'), {
      onProgress: (progress) => reports.push(progress),
    })

    assert('预检失败后仍然成功', reports.at(-1)?.stored === 1, JSON.stringify(reports.at(-1)))
    assert('重试时去掉了 As-Task', state.headers?.['as-task'] === undefined, String(state.headers?.['as-task']))
    assert('只发了一次正文', state.attempts === 1, `attempts=${state.attempts}`)
    server.close()
  }

  // ── 5. Task endpoint missing: upload still succeeds ──
  {
    const { server, state, base } = await withMock({ taskApi: false })
    let failed = null
    let done = false
    try {
      await uploadFile({ server: base, token: 'mock-token' }, '/d.ske', new File([payload], 'd.ske'), {})
      done = true
    } catch (err) {
      failed = err
    }

    assert('读不到任务不报错', !failed && done, failed?.message)
    assert('确实尝试过任务接口', state.polls > 0, `polls=${state.polls}`)
    server.close()
  }
} finally {
  for (const server of servers) server.close()
}

console.log(`\n${checks - failures}/${checks} 项通过`)
process.exit(failures ? 1 : 0)
