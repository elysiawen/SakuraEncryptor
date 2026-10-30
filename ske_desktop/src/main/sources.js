/**
 * Random-access byte sources for .ske containers.
 *
 * Both the local and the remote path expose the same tiny contract — `open()`,
 * `size`, `read(offset, length)`, `close()` — so the decrypting HTTP layer
 * never has to know where the ciphertext actually lives.
 */
import fs from 'node:fs'
import fsp from 'node:fs/promises'

/** A file on this machine, read through a persistent handle for cheap seeks. */
export class LocalSource {
  constructor(filePath) {
    this.filePath = filePath
    this.handle = null
    this.size = 0
  }

  async open() {
    const stat = await fsp.stat(this.filePath)
    if (!stat.isFile()) throw new Error('不是文件')
    this.size = stat.size
    this.handle = await fsp.open(this.filePath, 'r')
    return this
  }

  async read(offset, length) {
    const buffer = Buffer.allocUnsafe(length)
    const { bytesRead } = await this.handle.read(buffer, 0, length, offset)
    return bytesRead === length ? buffer : buffer.subarray(0, bytesRead)
  }

  async close() {
    if (this.handle) {
      await this.handle.close().catch(() => {})
      this.handle = null
    }
  }
}

/**
 * A remote container fetched with HTTP Range requests.
 *
 * The size is probed with `Range: bytes=0-0` rather than HEAD: AList's raw
 * links often sit behind a redirect chain where HEAD reports no length, and a
 * one-byte GET always answers with `Content-Range: bytes 0-0/<total>`.
 */
export class HttpSource {
  constructor(url, headers = {}) {
    this.url = url
    this.headers = headers
    this.size = 0
    this.ranged = false
    // Whole-file copy for upstreams without Range support, fetched once.
    this.whole = null
    this.wholeTask = null
  }

  async open() {
    const res = await fetch(this.url, {
      headers: { ...this.headers, Range: 'bytes=0-0' },
      redirect: 'follow',
    })
    if (!res.ok && res.status !== 206) {
      throw new Error(`上游返回 ${res.status}，无法读取加密文件`)
    }
    // Drain the probe body; Node keeps the socket busy otherwise. A server that
    // ignored Range has just handed us the whole file, so keep it instead.
    const probeBody = await res.arrayBuffer().catch(() => null)

    const contentRange = res.headers.get('content-range')
    if (contentRange) {
      const total = Number(/\/(\d+)$/.exec(contentRange)?.[1])
      if (Number.isFinite(total) && total > 0) {
        this.size = total
        this.ranged = true
      }
    }
    if (!this.ranged) {
      const length = Number(res.headers.get('content-length'))
      if (res.status === 206) {
        // A 206 without Content-Range is unusable: we cannot map block offsets.
        throw new Error('上游返回了分段响应但没有 Content-Range，无法定位数据块')
      }
      if (Number.isFinite(length) && length > 0) {
        // No range support at all: whole-file reads only. Seeking will be slow
        // but the player still works for small files.
        this.size = length
        this.ranged = false
        if (probeBody && probeBody.byteLength === length) {
          this.whole = Buffer.from(probeBody)
        }
      } else {
        throw new Error('无法确定上游文件大小')
      }
    }
    return this
  }

  async read(offset, length) {
    if (length <= 0) return Buffer.alloc(0)

    if (!this.ranged) {
      // Fallback: fetch once and slice. It used to re-download the file for
      // every read, which turned one cover-art probe into a dozen full
      // downloads on a storage that ignores Range; one copy is kept instead.
      if (!this.whole) {
        this.wholeTask ||= this.#fetchWhole()
        try {
          await this.wholeTask
        } finally {
          this.wholeTask = null
        }
      }
      if (!this.whole) throw new Error('上游读取失败')
      return this.whole.subarray(offset, offset + length)
    }

    const end = Math.min(offset + length, this.size) - 1
    const res = await fetch(this.url, {
      headers: { ...this.headers, Range: `bytes=${offset}-${end}` },
      redirect: 'follow',
    })
    if (res.status !== 206 && res.status !== 200) {
      throw new Error(`上游返回 ${res.status}`)
    }
    const buffer = Buffer.from(await res.arrayBuffer())
    return buffer.length > length ? buffer.subarray(0, length) : buffer
  }

  async #fetchWhole() {
    const res = await fetch(this.url, { headers: this.headers, redirect: 'follow' })
    if (!res.ok) throw new Error(`上游返回 ${res.status}`)
    const whole = Buffer.from(await res.arrayBuffer())
    this.size = whole.length
    this.whole = whole
    return whole
  }

  async close() {
    // fetch keeps no state between calls; only the fallback copy is worth freeing.
    this.whole = null
    this.wholeTask = null
  }
}

export function isRemote(target) {
  return /^https?:\/\//i.test(target)
}

export async function createSource(target, headers) {
  const source = isRemote(target) ? new HttpSource(target, headers) : new LocalSource(target)
  await source.open()
  return source
}

// LocalSource keeps an fs handle; export a helper so shutdown can be clean.
export async function closeQuietly(source) {
  if (!source) return
  try {
    await source.close()
  } catch {
    /* already gone */
  }
}

export { fs }
