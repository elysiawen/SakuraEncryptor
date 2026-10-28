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

  const server = http.createServer((req, res) => {
    let url
    try {
      url = new URL(req.url, 'http://127.0.0.1')
    } catch {
      sendText(res, 400, 'Bad request')
      return
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
