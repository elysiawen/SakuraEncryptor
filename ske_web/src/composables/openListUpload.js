/**
 * openListUpload — OpenList's `/api/fs/multipart/*` chunked upload.
 *
 * OpenList adds resumable chunked uploads on top of the shared `/api` surface,
 * so large encrypted files no longer ride on one long multipart request. The
 * protocol layer stays provider-neutral: `remoteClient.js` takes this in
 * through `options.uploadMultipart`, and `useOpenList.js` wires it up.
 */

import {
    normalizeUploadPath,
    readJson,
    MULTIPART_MAX_RETRIES,
    MULTIPART_FALLBACK_STATUS,
} from './remoteClient.js'

/**
 * Chunked upload: init → sequential chunks → complete.
 *
 * If the server hands back a resumed session it also reports the chunk ranges
 * it already holds (`received`, inclusive [start, end]); those are skipped.
 * Note that OpenList only resumes a `receiving` session when the request
 * carries a content hash matching the previous attempt, so without hashes a
 * retry normally starts a fresh session. Sequential sending stays clear of the
 * 409 "chunk already in flight" answer that parallel sends can hit.
 */
export async function uploadMultipart(ctx, absPath, file, { onProgress } = {}) {
    const target = normalizeUploadPath(absPath)
    const initRes = await fetch(`${ctx.server}/api/fs/multipart/init`, {
        method: 'POST',
        headers: {
            Authorization: ctx.token,
            'File-Path': target,
            'X-File-Size': String(file.size),
            // The server keeps this as the stored MIME type; an encrypted
            // container already carries application/octet-stream.
            'Content-Type': file.type || 'application/octet-stream',
        },
    })
    const initJson = await readJson(initRes)
    if (!initRes.ok || initJson?.code !== 200) {
        const err = new Error(initJson?.message || '分片上传初始化失败')
        err.multipartUnsupported = MULTIPART_FALLBACK_STATUS.has(initRes.status)
        throw err
    }

    const session = initJson.data || {}
    const uploadId = session.upload_id
    const chunkSize = Number(session.chunk_size) || 10 * 1024 * 1024
    const totalChunks = Number(session.total_chunks) || Math.ceil(file.size / chunkSize)
    if (!uploadId) {
        const err = new Error('分片上传响应缺少 upload_id')
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
    onProgress?.(Math.min(1, receivedBytes / file.size))

    try {
        for (let index = 0; index < totalChunks; index += 1) {
            if (received.has(index)) continue
            const start = index * chunkSize
            const blob = file.slice(start, Math.min(file.size, start + chunkSize))
            const snapshot = await sendChunk(ctx, uploadId, index, blob)
            receivedBytes = Math.max(receivedBytes, Number(snapshot?.received_bytes) || 0)
            onProgress?.(Math.min(1, receivedBytes / file.size))
        }

        const completeRes = await fetch(`${ctx.server}/api/fs/multipart/complete`, {
            method: 'POST',
            headers: { Authorization: ctx.token, 'X-Upload-Id': uploadId },
        })
        const completeJson = await readJson(completeRes)
        if (!completeRes.ok || completeJson?.code !== 200) {
            throw new Error(completeJson?.message || '分片上传合并失败')
        }
        onProgress?.(1)
        return completeJson.data
    } catch (err) {
        // Best effort: do not leave a session (and its temp chunks) behind.
        fetch(`${ctx.server}/api/fs/multipart/abort`, {
            method: 'POST',
            headers: { Authorization: ctx.token, 'X-Upload-Id': uploadId },
        }).catch(() => {})
        throw err
    }
}

async function sendChunk(ctx, uploadId, index, blob) {
    for (let attempt = 0; attempt < MULTIPART_MAX_RETRIES; attempt += 1) {
        const res = await fetch(`${ctx.server}/api/fs/multipart/chunk`, {
            method: 'PUT',
            headers: {
                Authorization: ctx.token,
                'X-Upload-Id': uploadId,
                'X-Chunk-Index': String(index),
            },
            body: blob,
        })
        const json = await readJson(res)
        if (res.ok && json?.code === 200) return json.data
        // 429 = receiving window full, 409 = same chunk in flight elsewhere.
        if (res.status === 429 || res.status === 409) {
            await new Promise(resolve => setTimeout(resolve, 300 + attempt * 300))
            continue
        }
        throw new Error(json?.message || `分片 ${index} 上传失败`)
    }
    throw new Error(`分片 ${index} 上传失败：服务器持续繁忙，请稍后重试`)
}
