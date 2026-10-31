/**
 * Fetching and installing the local server binaries.
 *
 * Nothing is bundled with the app: both backends are ~35-50 MB Go programs, and
 * shipping four platform builds would triple the installer size. Instead the
 * first time a user turns the embedded server on, the matching release asset is
 * downloaded from GitHub, verified against the `sha256` GitHub publishes for
 * every asset, and unpacked under `userData`.
 *
 * The version follows upstream's "latest" on purpose (see `backends.js`). What
 * makes that safe is `server.js`: the initialisation path is discovered at
 * runtime, so a release that reorganises its setup endpoints degrades instead of
 * breaking.
 */
import { app } from 'electron'
import crypto from 'node:crypto'
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { Readable } from 'node:stream'
import extractZip from 'extract-zip'
import * as tar from 'tar'
import { binaryName, normalizeBackend, pickAsset } from './backends.js'

/** GitHub's unauthenticated API allows 60 requests an hour per IP. */
const RELEASE_TTL_MS = 6 * 60 * 60 * 1000

/** Fetching 50 MB over a bad link must not hold the process open forever. */
const DOWNLOAD_TIMEOUT_MS = 15 * 60 * 1000

export function embeddedRoot() {
  return path.join(app.getPath('userData'), 'embedded')
}

export function backendDir(backendId) {
  return path.join(embeddedRoot(), normalizeBackend(backendId).id)
}

function releaseCachePath() {
  return path.join(embeddedRoot(), 'releases.json')
}

async function readJson(file, fallback) {
  try {
    return JSON.parse(await fsp.readFile(file, 'utf8'))
  } catch {
    return fallback
  }
}

const GH_HEADERS = {
  Accept: 'application/vnd.github+json',
  'User-Agent': 'Sakura-Encryptor-Desktop',
}

/**
 * Resolve a GitHub release, from cache when possible.
 *
 * @param {string} backendId
 * @param {string} version `latest` or an explicit tag such as `v4.2.9`
 * @param {{ force?: boolean }} [options]
 */
export async function resolveRelease(backendId, version = 'latest', { force = false } = {}) {
  const backend = normalizeBackend(backendId)
  const key = `${backend.repo}@${version}`
  const cache = await readJson(releaseCachePath(), {})
  const hit = cache[key]

  // `latest` may move; an explicit tag never does, so it can be cached forever.
  const fresh =
    hit && (version !== 'latest' || Date.now() - (hit.fetchedAt || 0) < RELEASE_TTL_MS)
  if (fresh && !force) {
    if (hit.error) throw new Error(hit.error)
    return hit.release
  }

  const url =
    version === 'latest'
      ? `https://api.github.com/repos/${backend.repo}/releases/latest`
      : `https://api.github.com/repos/${backend.repo}/releases/tags/${encodeURIComponent(version)}`

  try {
    const res = await fetch(url, { headers: GH_HEADERS, signal: AbortSignal.timeout(30_000) })
    if (!res.ok) {
      const detail = res.status === 403 || res.status === 429 ? '（GitHub 访问频率受限，请稍后再试）' : ''
      throw new Error(`获取 ${backend.label} 版本信息失败：HTTP ${res.status}${detail}`)
    }
    const release = await res.json()
    cache[key] = { fetchedAt: Date.now(), release }
    await fsp.mkdir(embeddedRoot(), { recursive: true })
    await fsp.writeFile(releaseCachePath(), JSON.stringify(cache, null, 2), 'utf8')
    return release
  } catch (err) {
    // Keep the failure around briefly so a flapping network cannot turn into a
    // request storm, but surface the original reason.
    const message = err?.name === 'TimeoutError' ? '访问 GitHub 超时，请检查网络或填写镜像' : err.message
    cache[key] = { fetchedAt: Date.now(), error: message }
    await fsp.mkdir(embeddedRoot(), { recursive: true }).catch(() => {})
    await fsp.writeFile(releaseCachePath(), JSON.stringify(cache, null, 2), 'utf8').catch(() => {})
    throw new Error(message)
  }
}

/**
 * Stream a URL to disk while hashing it.
 *
 * @returns {Promise<string>} hex sha256 of what was written
 */
async function download(url, file, { onProgress }) {
  const res = await fetch(url, {
    headers: { 'User-Agent': GH_HEADERS['User-Agent'] },
    redirect: 'follow',
    signal: AbortSignal.timeout(DOWNLOAD_TIMEOUT_MS),
  })
  if (!res.ok || !res.body) throw new Error(`下载失败：HTTP ${res.status}`)

  const total = Number(res.headers.get('content-length')) || 0
  const hash = crypto.createHash('sha256')
  let received = 0
  let lastTick = 0

  const meter = new Transform({
    transform(chunk, _enc, done) {
      hash.update(chunk)
      received += chunk.length
      const now = Date.now()
      // Progress is a UI nicety: never let it throttle the actual transfer.
      if (now - lastTick > 150) {
        lastTick = now
        onProgress?.({ received, total })
      }
      done(null, chunk)
    },
  })

  await pipeline(Readable.fromWeb(res.body), meter, fs.createWriteStream(file))
  onProgress?.({ received, total: total || received })
  return hash.digest('hex')
}

/** Depth-first search for the executable, tolerating a wrapper folder. */
async function findBinary(dir, backendId) {
  const wanted = binaryName(backendId)
  const stack = [dir]
  const others = []
  while (stack.length) {
    const current = stack.pop()
    let entries
    try {
      entries = await fsp.readdir(current, { withFileTypes: true })
    } catch {
      continue
    }
    for (const entry of entries) {
      const full = path.join(current, entry.name)
      if (entry.isDirectory()) {
        stack.push(full)
        continue
      }
      if (entry.name === wanted) return full
      if (!/\.(txt|md|json|sha256|sig)$/i.test(entry.name)) others.push(full)
    }
  }
  // A renamed binary is still the only real candidate in these archives.
  if (others.length === 1) return others[0]
  throw new Error('安装包内没有找到可执行文件')
}

/**
 * Download and install one backend, returning what is now on disk.
 *
 * @param {string} backendId
 * @param {{ version?: string, mirrors?: string[], onProgress?: (p: object) => void }} [options]
 */
export async function install(backendId, { version = 'latest', mirrors = [], onProgress } = {}) {
  const backend = normalizeBackend(backendId)
  const release = await resolveRelease(backend.id, version)
  const asset = pickAsset(release, backend.id)
  const dir = backendDir(backend.id)
  const workDir = path.join(dir, 'download')

  await fsp.mkdir(workDir, { recursive: true })
  const archive = path.join(workDir, asset.name)

  // A bare download first, then any mirror the user configured. Mirrors simply
  // prefix the canonical URL, which is how the Chinese proxies work.
  const attempts = ['', ...mirrors.filter((m) => typeof m === 'string' && m.trim())].map((prefix) =>
    prefix ? `${prefix.replace(/\/+$/, '')}/${asset.url}` : asset.url,
  )

  let sha = ''
  let lastError = null
  for (const url of attempts) {
    try {
      onProgress?.({ phase: 'download', received: 0, total: asset.size })
      sha = await download(url, archive, { onProgress: (p) => onProgress?.({ phase: 'download', ...p }) })
      lastError = null
      break
    } catch (err) {
      lastError = err
      await fsp.rm(archive, { force: true }).catch(() => {})
    }
  }
  if (lastError) throw new Error(`${backend.label} 下载失败：${lastError.message}`)

  if (asset.digest && asset.digest.startsWith('sha256:')) {
    const expected = asset.digest.slice('sha256:'.length)
    if (expected && sha !== expected) {
      await fsp.rm(archive, { force: true }).catch(() => {})
      throw new Error(`${backend.label} 安装包校验失败（sha256 不匹配），已丢弃`)
    }
  }

  onProgress?.({ phase: 'extract' })
  const staging = path.join(workDir, `x-${Date.now()}`)
  await fsp.mkdir(staging, { recursive: true })
  try {
    if (asset.name.endsWith('.zip')) await extractZip(archive, { dir: staging })
    else await tar.x({ file: archive, cwd: staging })
    const found = await findBinary(staging, backend.id)

    const targetDir = path.join(dir, 'bin', release.tag_name)
    await fsp.rm(targetDir, { recursive: true, force: true })
    await fsp.mkdir(targetDir, { recursive: true })
    const target = path.join(targetDir, path.basename(found))
    await fsp.copyFile(found, target)
    if (process.platform !== 'win32') await fsp.chmod(target, 0o755)

    const record = {
      backend: backend.id,
      tag: String(release.tag_name),
      asset: asset.name,
      size: asset.size || 0,
      installedAt: new Date().toISOString(),
      path: target,
    }
    await fsp.writeFile(path.join(dir, 'version.json'), JSON.stringify(record, null, 2), 'utf8')
    onProgress?.({ phase: 'done', tag: record.tag })
    return record
  } finally {
    await fsp.rm(staging, { recursive: true, force: true }).catch(() => {})
    await fsp.rm(archive, { force: true }).catch(() => {})
  }
}

/** What is installed right now, or null. Never touches the network. */
export async function installed(backendId) {
  const dir = backendDir(backendId)
  const record = await readJson(path.join(dir, 'version.json'), null)
  if (!record?.path) return null
  try {
    await fsp.access(record.path, fs.constants.X_OK)
  } catch {
    // The user may have cleaned `userData` by hand; treat it as not installed
    // rather than letting spawn fail with ENOENT later.
    return null
  }
  return record
}

/** Remove the binaries and every scrap of server data for one backend. */
export async function uninstall(backendId) {
  await fsp.rm(backendDir(backendId), { recursive: true, force: true })
}
