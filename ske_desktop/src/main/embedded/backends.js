/**
 * The servers this app can run locally, and the release assets that carry them.
 *
 * Both speak the same AList-derived `/api`, so once one is running the rest of
 * the app cannot tell them apart — `cloud.js` only needs `provider` to pick a
 * client, and `openlist` merely adds chunked uploads. Everything that genuinely
 * differs (asset naming, whether a `-lite` build exists) lives here instead of
 * being scattered through the runtime.
 *
 * Versions are deliberately NOT pinned: the downloader follows the upstream
 * "latest" release and adapts at runtime (see `server.js`), because pinning a
 * tag would mean shipping a desktop update every time upstream moves.
 */

/**
 * Insertion order is the order the picker shows: AList first, because it is the
 * project most users already run.
 */
export const BACKENDS = {
  alist: {
    id: 'alist',
    label: 'AList',
    repo: 'AlistGo/alist',
    exeBase: 'alist',
    preferLite: false,
    /** Shown in the picker: what each backend is better at. */
    blurb: '更老牌、驱动更多；上传走普通请求。',
  },
  openlist: {
    id: 'openlist',
    label: 'OpenList',
    repo: 'OpenListTeam/OpenList',
    exeBase: 'openlist',
    /**
     * The lite build drops ffmpeg and the offline-download tools. The app only
     * speaks HTTP to the API, so a third of the download buys nothing.
     */
    preferLite: true,
    blurb: 'AList 的社区分支，额外支持分片上传，大文件更稳。',
  },
}

export const DEFAULT_BACKEND = 'openlist'

export function normalizeBackend(id) {
  return Object.hasOwn(BACKENDS, id) ? BACKENDS[id] : BACKENDS[DEFAULT_BACKEND]
}

/** Node's `process.arch` → the suffix upstream puts in its asset names. */
function releaseArch(backendId, arch) {
  switch (arch) {
    case 'x64':
      return 'amd64'
    case 'arm64':
      return 'arm64'
    case 'ia32':
      return '386'
    case 'arm':
      // AList spells the 32-bit ARM build "arm-7"; OpenList keeps "arm".
      return backendId === 'alist' ? 'arm-7' : 'arm'
    default:
      return arch
  }
}

function releasePlatform(platform) {
  if (platform === 'win32') return 'windows'
  if (platform === 'darwin') return 'darwin'
  if (platform === 'linux') return 'linux'
  return platform
}

/**
 * Asset candidates for this machine, best first.
 *
 * `-lite` is tried before the full build because OpenList only grew a lite
 * variant recently; asking for a name that does not exist must degrade to the
 * bigger package rather than fail the download outright.
 */
export function assetCandidates(backendId, { platform = process.platform, arch = process.arch } = {}) {
  const backend = normalizeBackend(backendId)
  const os = releasePlatform(platform)
  const cpu = releaseArch(backend.id, arch)
  const ext = platform === 'win32' ? 'zip' : 'tar.gz'
  const base = `${backend.exeBase}-${os}-${cpu}`
  const names = []
  if (backend.preferLite) names.push(`${base}-lite.${ext}`)
  names.push(`${base}.${ext}`)
  return names
}

/** Name of the binary inside the archive (and on disk once installed). */
export function binaryName(backendId, platform = process.platform) {
  const backend = normalizeBackend(backendId)
  return platform === 'win32' ? `${backend.exeBase}.exe` : backend.exeBase
}

/**
 * Look a tag up in a release payload and describe what to download.
 *
 * @param {object} release GitHub release JSON
 * @param {string} backendId
 * @returns {{ tag: string, name: string, url: string, digest: string, size: number }}
 */
export function pickAsset(release, backendId, platform = process.platform, arch = process.arch) {
  const assets = Array.isArray(release?.assets) ? release.assets : []
  for (const name of assetCandidates(backendId, { platform, arch })) {
    const asset = assets.find((a) => a.name === name)
    if (asset) {
      return {
        tag: release.tag_name,
        name: asset.name,
        url: asset.browser_download_url,
        // GitHub attaches `sha256:...` to every asset it hosts, so no separate
        // checksum file is needed and nothing has to be trusted blindly.
        digest: typeof asset.digest === 'string' ? asset.digest : '',
        size: Number(asset.size) || 0,
      }
    }
  }
  throw new Error(
    `该版本没有适用于当前平台的 ${normalizeBackend(backendId).label} 安装包（${platform}/${arch}）`,
  )
}
