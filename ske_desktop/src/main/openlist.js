/**
 * OpenList backend entry point.
 *
 * OpenList is a community fork of AList and keeps the same `/api` surface, so
 * the shared protocol layer lives in `remote-client.js`. Only what the fork
 * adds belongs here:
 *
 *  - `/api/fs/multipart/*` chunked upload for large containers
 *
 * OTP login is *not* listed: both servers accept `otp_code` and answer 402, so
 * that path is shared.
 */
import {
  MULTIPART_FALLBACK_STATUS,
  MULTIPART_MAX_RETRIES,
  RemoteClient,
  RemoteError,
  blobFor,
  delay,
  normalizeUploadPath,
  readJson,
} from './remote-client.js'

export class OpenListClient extends RemoteClient {
  /** No other backend has the chunked endpoints yet. */
  supportsMultipartUpload = true

  constructor(options) {
    super({ ...options, label: 'OpenList' })
  }

  /**
   * Chunked upload: init → sequential chunks → complete.
   *
   * A resumed session comes back with the ranges the server already holds
   * (`received`, inclusive [start, end]); those are skipped. OpenList only
   * resumes a `receiving` session when the request carries a matching content
   * hash, so without one a retry normally starts fresh — the skip is harmless.
   * Sequential sending stays clear of the 409 "chunk already in flight" answer
   * that parallel sends can hit.
   */
  async uploadMultipart(absPath, filePath, size, { signal, onProgress } = {}) {
    const target = normalizeUploadPath(absPath)
    const file = await blobFor(filePath)

    let initRes
    try {
      initRes = await fetch(`${this.server}/api/fs/multipart/init`, {
        method: 'POST',
        headers: {
          ...(this.token ? { Authorization: this.token } : {}),
          'File-Path': target,
          'X-File-Size': String(size),
          'Content-Type': 'application/octet-stream',
        },
        signal,
      })
    } catch (err) {
      if (err.name === 'AbortError') throw new Error('已取消')
      throw new RemoteError(`分片上传初始化失败：${err.message}`)
    }

    const initJson = await readJson(initRes)
    if (!initRes.ok || initJson?.code !== 200) {
      const err = new RemoteError(initJson?.message || '分片上传初始化失败', initJson?.code)
      err.multipartUnsupported = MULTIPART_FALLBACK_STATUS.has(initRes.status)
      throw err
    }

    const session = initJson.data || {}
    const uploadId = session.upload_id
    const chunkSize = Number(session.chunk_size) || 10 * 1024 * 1024
    const totalChunks = Number(session.total_chunks) || Math.ceil(size / chunkSize)
    if (!uploadId) {
      const err = new RemoteError('分片上传响应缺少 upload_id')
      err.multipartUnsupported = true
      throw err
    }

    // Chunks the server already persisted from an earlier interrupted attempt.
    const received = new Set()
    for (const range of Array.isArray(session.received) ? session.received : []) {
      const from = Number(range?.[0])
      const to = Number(range?.[1])
      if (!Number.isFinite(from) || !Number.isFinite(to)) continue
      for (let index = from; index <= to && index < totalChunks; index += 1) {
        received.add(index)
      }
    }
    let receivedBytes = Number(session.received_bytes) || 0
    onProgress?.(Math.min(1, receivedBytes / Math.max(1, size)))

    try {
      for (let index = 0; index < totalChunks; index += 1) {
        if (received.has(index)) continue
        if (signal?.aborted) throw new Error('已取消')
        const start = index * chunkSize
        const blob = file.slice(start, Math.min(size, start + chunkSize))
        const snapshot = await this.#sendChunk(uploadId, index, blob, signal)
        receivedBytes = Math.max(receivedBytes, Number(snapshot?.received_bytes) || 0)
        onProgress?.(Math.min(1, receivedBytes / Math.max(1, size)))
      }

      const completeRes = await fetch(`${this.server}/api/fs/multipart/complete`, {
        method: 'POST',
        headers: { Authorization: this.token, 'X-Upload-Id': uploadId },
        signal,
      })
      const completeJson = await readJson(completeRes)
      if (!completeRes.ok || completeJson?.code !== 200) {
        throw new RemoteError(completeJson?.message || '分片上传合并失败', completeJson?.code)
      }
      onProgress?.(1)
      return true
    } catch (err) {
      if (err.name === 'AbortError' || signal?.aborted) throw new Error('已取消')
      // Best effort: do not leave a session (and its temp chunks) behind. The
      // caller's signal is already aborted or failed, so this uses its own.
      fetch(`${this.server}/api/fs/multipart/abort`, {
        method: 'POST',
        headers: { Authorization: this.token, 'X-Upload-Id': uploadId },
      }).catch(() => {})
      throw err
    }
  }

  async #sendChunk(uploadId, index, blob, signal) {
    for (let attempt = 0; attempt < MULTIPART_MAX_RETRIES; attempt += 1) {
      if (signal?.aborted) throw new Error('已取消')

      let res
      try {
        res = await fetch(`${this.server}/api/fs/multipart/chunk`, {
          method: 'PUT',
          headers: {
            Authorization: this.token,
            'X-Upload-Id': uploadId,
            'X-Chunk-Index': String(index),
          },
          body: blob,
          signal,
        })
      } catch (err) {
        if (err.name === 'AbortError') throw err
        throw new RemoteError(`分片 ${index} 上传失败：${err.message}`)
      }

      const json = await readJson(res)
      if (res.ok && json?.code === 200) return json.data
      // 429 = receiving window full, 409 = same chunk in flight elsewhere.
      if (res.status === 429 || res.status === 409) {
        await delay(300 + attempt * 300, signal)
        continue
      }
      throw new RemoteError(json?.message || `分片 ${index} 上传失败`, json?.code)
    }
    throw new RemoteError(`分片 ${index} 上传失败：服务器持续繁忙，请稍后重试`)
  }
}
