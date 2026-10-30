/**
 * Loopback HTTP server.
 *
 * Why a real HTTP origin instead of a custom protocol: Chromium only registers
 * service workers, and only treats range-backed media the same way as a website,
 * on an http(s) origin. `http://127.0.0.1:<random>` gives us that for free, so
 * the whole web-side decrypt pipeline (including the existing Service Worker)
 * keeps working unchanged.
 *
 * The port is random per launch and every request must carry the token, which
 * keeps other local processes from reading decrypted media out of the server.
 */
import http from 'node:http'
import fsp from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { createMediaHandler } from './media-router.js'

const STATIC_MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.wasm': 'application/wasm',
}

/** A preview is a thumbnail, not a photo: refuse anything that pretends otherwise. */
const MAX_REMOTE_THUMB_BYTES = 16 * 1024 * 1024
const REMOTE_THUMB_TIMEOUT_MS = 15_000

function sendText(res, status, text) {
  res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8' })
  res.end(text)
}

function tokenOk(url, req, token) {
  const provided = url.searchParams.get('t') || req.headers['x-ske-token']
  if (!provided || typeof provided !== 'string') return false
  const a = Buffer.from(provided)
  const b = Buffer.from(token)
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

/** Serve the built renderer from disk, refusing anything outside its root. */
async function serveStatic(res, root, pathname) {
  const relative = pathname === '/' ? 'index.html' : decodeURIComponent(pathname).replace(/^\/+/, '')
  const resolved = path.resolve(root, relative)
  const rootResolved = path.resolve(root)

  if (resolved !== rootResolved && !resolved.startsWith(rootResolved + path.sep)) {
    sendText(res, 403, 'Forbidden')
    return
  }

  try {
    const data = await fsp.readFile(resolved)
    res.writeHead(200, {
      'Content-Type': STATIC_MIME[path.extname(resolved).toLowerCase()] || 'application/octet-stream',
      'Content-Length': data.length,
      'Cache-Control': 'no-cache',
    })
    res.end(data)
  } catch (err) {
    if (err.code === 'ENOENT' || err.code === 'EISDIR') {
      // SPA fallback: unknown paths belong to the router, not the file system.
      try {
        const index = await fsp.readFile(path.join(root, 'index.html'))
        res.writeHead(200, {
          'Content-Type': 'text/html; charset=utf-8',
          'Content-Length': index.length,
          'Cache-Control': 'no-cache',
        })
        res.end(index)
      } catch {
        sendText(res, 404, 'Not found')
      }
      return
    }
    sendText(res, 500, err.message)
  }
}

/**
 * @param {object} options
 * @param {import('./media-router.js').MediaRegistry} options.registry
 * @param {string} [options.rendererDir] directory of the built renderer
 * @returns {Promise<{ port: number, token: string, origin: string, close: () => Promise<void> }>}
 */
export async function startServer({ registry, rendererDir, cloud }) {
  const token = crypto.randomBytes(24).toString('hex')
  /**
   * Dev mode serves the page from Vite on another origin, so the renderer's
   * ranged reads (cover art) become cross-origin requests and need CORS. With
   * `rendererDir` set the page is same-origin and the stricter policy stands.
   */
  const devCors = !rendererDir
  const corsPaths = ['/media/', '/thumb/', '/remote-thumb/']
  const handleMedia = createMediaHandler(registry)

  /**
   * Preview endpoint: `/thumb/<sessionId>/<base64url(plainPath)>`.
   *
   * The path is encoded so a nested name cannot change the route shape, and the
   * session id keeps the media password entirely on this side.
   */
  async function handleThumb(req, res, url) {
    const rest = url.pathname.slice('/thumb/'.length)
    const slash = rest.indexOf('/')
    if (slash <= 0) {
      sendText(res, 400, 'Bad thumbnail request')
      return
    }

    const sessionId = rest.slice(0, slash)
    const plainPath = Buffer.from(rest.slice(slash + 1), 'base64url').toString('utf8')

    if (!cloud) {
      sendText(res, 404, 'Not available')
      return
    }

    try {
      const jpeg = await cloud.thumbnail(sessionId, plainPath)
      if (!jpeg) {
        // No preview in the container: the UI falls back to a type icon.
        sendText(res, 404, 'No preview')
        return
      }
      res.writeHead(200, {
        'Content-Type': 'image/jpeg',
        'Content-Length': jpeg.length,
        'Cache-Control': 'private, max-age=300',
      })
      res.end(jpeg)
    } catch (err) {
      console.error('[thumb]', err.message)
      sendText(res, 404, 'Preview failed')
    }
  }

  /**
   * Preview endpoint for plain files: `/remote-thumb/<sessionId>/<base64url(path)>`.
   *
   * The storage already made this picture (AList's `thumb`), often on a CDN the
   * renderer cannot reach under the app's CSP — and whose signed link has no
   * business in the renderer either. Main fetches it and hands back the bytes.
   * A 4xx means the signature expired (they last hours, not forever), so the
   * link is re-resolved once before giving up.
   */
  async function handleRemoteThumb(req, res, url) {
    const rest = url.pathname.slice('/remote-thumb/'.length)
    const slash = rest.indexOf('/')
    if (slash <= 0) {
      sendText(res, 400, 'Bad thumbnail request')
      return
    }

    const sessionId = rest.slice(0, slash)
    const plainPath = Buffer.from(rest.slice(slash + 1), 'base64url').toString('utf8')

    if (!cloud) {
      sendText(res, 404, 'Not available')
      return
    }

    let target = await cloud.remoteThumbUrl(sessionId, plainPath).catch((err) => {
      console.error('[remote-thumb]', err.message)
      return null
    })

    for (let attempt = 0; attempt < 2 && target; attempt += 1) {
      let upstream
      try {
        upstream = await fetch(target, { signal: AbortSignal.timeout(REMOTE_THUMB_TIMEOUT_MS) })
      } catch (err) {
        console.error('[remote-thumb]', err.message)
        break
      }

      if (upstream.ok) {
        const bytes = Buffer.from(await upstream.arrayBuffer())
        if (bytes.length === 0) break
        if (bytes.length > MAX_REMOTE_THUMB_BYTES) {
          sendText(res, 502, 'Preview too large')
          return
        }
        res.writeHead(200, {
          'Content-Type': upstream.headers.get('content-type') || 'image/jpeg',
          'Content-Length': bytes.length,
          // Chromium re-uses this for tiles that scroll back into view.
          'Cache-Control': 'private, max-age=3600',
        })
        res.end(req.method === 'HEAD' ? undefined : bytes)
        return
      }

      if (attempt > 0 || upstream.status < 400) break
      target = await cloud.remoteThumbUrl(sessionId, plainPath, { refresh: true }).catch(() => null)
    }

    // The UI falls back to the original image / a captured frame.
    sendText(res, 404, 'No preview')
  }

  const server = http.createServer((req, res) => {
    let url
    try {
      url = new URL(req.url, 'http://127.0.0.1')
    } catch {
      sendText(res, 400, 'Bad request')
      return
    }

    if (devCors && corsPaths.some((prefix) => url.pathname.startsWith(prefix))) {
      res.setHeader('Access-Control-Allow-Origin', '*')
      // `Content-Range` is not a CORS-safelisted response header: without this
      // the dev renderer reads null for the total size, which is exactly what
      // end-of-file metadata (mp4 `moov`) needs to be found at all.
      res.setHeader('Access-Control-Expose-Headers', 'Content-Range, Accept-Ranges')
      if (req.method === 'OPTIONS') {
        res.writeHead(204, {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Headers': 'Range',
          'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
          'Access-Control-Max-Age': '600',
        })
        res.end()
        return
      }
    }

    // Media requests come from <video src>, which cannot attach headers, so the
    // token rides in the query string for those and in a header elsewhere.
    if (url.pathname.startsWith('/media/')) {
      if (!tokenOk(url, req, token)) {
        sendText(res, 403, 'Forbidden')
        return
      }
      handleMedia(req, res, url).catch((err) => {
        console.error('[media] unhandled:', err)
        if (!res.headersSent) sendText(res, 500, err.message)
        else res.destroy(err)
      })
      return
    }

    if (url.pathname.startsWith('/remote-thumb/')) {
      if (!tokenOk(url, req, token)) {
        sendText(res, 403, 'Forbidden')
        return
      }
      handleRemoteThumb(req, res, url).catch((err) => {
        console.error('[remote-thumb] unhandled:', err)
        if (!res.headersSent) sendText(res, 500, err.message)
      })
      return
    }

    if (url.pathname.startsWith('/thumb/')) {
      if (!tokenOk(url, req, token)) {
        sendText(res, 403, 'Forbidden')
        return
      }
      handleThumb(req, res, url).catch((err) => {
        console.error('[thumb] unhandled:', err)
        if (!res.headersSent) sendText(res, 500, err.message)
      })
      return
    }

    if (req.method !== 'GET' && req.method !== 'HEAD') {
      sendText(res, 405, 'Method not allowed')
      return
    }

    // Static assets are not token-gated: the shell itself carries no secrets,
    // and a browser needs to fetch index.html + hashed bundles by plain URL.
    // Everything that reveals data lives under /media and does require the token.
    if (!rendererDir) {
      sendText(res, 404, 'Renderer not built')
      return
    }

    serveStatic(res, rendererDir, url.pathname).catch((err) => {
      console.error('[static]', err)
      if (!res.headersSent) sendText(res, 500, err.message)
    })
  })

  server.keepAliveTimeout = 65_000

  await new Promise((resolve, reject) => {
    server.once('error', reject)
    // Port 0 = let the OS pick a free one; 127.0.0.1 keeps it off the network.
    server.listen(0, '127.0.0.1', resolve)
  })

  const { port } = server.address()

  return {
    port,
    token,
    origin: `http://127.0.0.1:${port}`,
    close: () =>
      new Promise((resolve) => {
        server.closeAllConnections?.()
        server.close(() => resolve())
      }),
  }
}
