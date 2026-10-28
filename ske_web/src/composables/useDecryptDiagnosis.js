/**
 * useDecryptDiagnosis — ask the worker *why* a decrypt-proxy URL failed.
 *
 * An `<img>`, `<video>` or `<audio>` element reports exactly one thing: "it did
 * not load". It cannot see the status, the headers or the body. So when one of
 * them fails we re-request the same proxy URL ourselves — same-origin, so the
 * status and `X-Ske-Error` are readable — and the worker names the cause:
 *
 *   401 NO_KEY         the worker has no password yet
 *   403 BAD_PASSWORD   the password does not open this container
 *   422 CORRUPT        the bytes were altered / the container is broken
 *   502 UPSTREAM_BLOCKED  CORS refused, 401/403 from the host, or offline
 *   500 UNKNOWN        anything else (console has the detail)
 *   2xx OK             decrypt works, so the browser itself refused the format
 *
 * One cause per failure, never a list of guesses: "可能是 A、B 或 C" tells a
 * user nothing and gives them nothing to check.
 */
import { ref } from 'vue'
import { helperAvailable, suggestDrive } from './useExtensionBridge.js'
import { RELEASES_URL } from './useProjectLinks.js'

/** Origin of the drive behind a decrypt-proxy URL; '' for local vault files. */
function upstreamOrigin(proxyUrl) {
  try {
    const target = new URL(proxyUrl, location.href).searchParams.get('url')
    return target ? new URL(target).origin : ''
  } catch {
    return ''
  }
}

const TEXT = {
  NO_KEY: '解密密码没有送达后台解密器（Service Worker 已休眠），请返回重新输入一次密码。',
  BAD_PASSWORD: '密码不正确，无法解密这个文件。',
  CORRUPT: '解密失败：文件内容已损坏或被改动，完整性校验不通过。',
  OK: '文件可以正常解密，但浏览器无法解码它（通常是不支持的图片 / 视频编码，或扩展名与实际格式不符）。',
  UNREACHABLE: '请求没有完成：可能是网络中断、页面离线，或 Service Worker 没有运行。',
}

const UPSTREAM = {
  cors: {
    title: '该网盘的跨域请求被拒绝',
    // This panel has no button, so it does not ask — it either names the helper
    // that is already installed (the caller then appends the outcome) or points
    // at where to get one.
    text: (origin) =>
      `浏览器直连 ${origin || '该网盘'} 被跨域策略（CORS）拦截，` +
      (helperAvailable()
        ? '当前已安装插件。'
        : '请使用浏览器插件，或直接下载客户端使用。\n' +
          `下载地址：${RELEASES_URL}`),
  },
  forbidden: {
    title: '网盘拒绝访问',
    text: '网盘拒绝了直链请求（HTTP 403）：通常是直链已过期、需要登录，或开启了防盗链。\n' +
      '请改用桌面客户端或安卓客户端浏览。',
  },
  offline: {
    title: '网络不可用',
    text: '网络已断开，请恢复网络后重试。',
  },
}

/**
 * Re-request [proxyUrl] just far enough to learn the status code.
 *
 * `Range: bytes=0-0` keeps the successful case to a single byte, and cancels the
 * body so a diagnosis never pulls real payload.
 */
export async function diagnoseDecrypt(proxyUrl) {
  if (!proxyUrl) return { code: 'UNKNOWN', status: 0 }

  let resp
  try {
    resp = await fetch(proxyUrl, {
      headers: { Range: 'bytes=0-0' },
      cache: 'no-store',
    })
  } catch {
    return { code: 'UNREACHABLE', status: 0 }
  }

  if (resp.ok) {
    resp.body?.cancel().catch(() => {})
    return { code: 'OK', status: resp.status }
  }

  return {
    code: resp.headers.get('X-Ske-Error') || 'UNKNOWN',
    reason: resp.headers.get('X-Ske-Reason') || '',
    status: resp.status,
  }
}

/** The sentence to show for a diagnosis: one cause, one instruction. */
export function describeDiagnosis(result, origin = '') {
  if (!result) return ''

  if (result.code === 'UPSTREAM_BLOCKED') {
    const entry = UPSTREAM[result.reason] || UPSTREAM.cors
    const text = typeof entry.text === 'function' ? entry.text(origin) : entry.text
    return `${entry.title}。${text}`
  }

  if (result.code === 'UNKNOWN') {
    // A 404 here means the worker is not intercepting yet: nothing decrypted,
    // nothing to blame on the file.
    if (result.status === 404) {
      return '解密代理没有接管这次请求（Service Worker 尚未激活）。刷新页面后重试。'
    }
    return `解密失败（HTTP ${result.status}）：具体错误见浏览器控制台的 [SW] 日志。`
  }

  return TEXT[result.code] || `解密失败（HTTP ${result.status}）。`
}

/**
 * Wire a component's failure state to a diagnosis.
 *
 * @param {() => string} getProxyUrl the URL that just failed
 */
export function useFailureDiagnosis(getProxyUrl) {
  const detail = ref('')
  let token = 0

  async function examine() {
    const url = getProxyUrl()
    if (!url) return

    // A playlist can fail several files in a row; only the newest answer counts.
    const mine = ++token
    detail.value = ''
    const result = await diagnoseDecrypt(url)
    if (mine !== token) return

    const origin = upstreamOrigin(url)
    detail.value = describeDiagnosis(result, origin)

    if (result.code === 'UPSTREAM_BLOCKED' && result.reason === 'cors') {
      // No button in here, so the drive goes to the helper straight away — the
      // user should not have to retype a hostname they only saw in a message —
      // and whatever the helper answered is appended under the cause.
      const reply = await suggestDrive(origin)
      if (mine !== token || !reply) return
      detail.value += reply.auto
        ? '\n已自动加入跨域小助手，刷新页面即可解密。'
        : '\n已在跨域小助手中登记：打开插件点「添加」并允许授权，授权成功即自动加入（面板可能被关闭），回到页面刷新就能解密。'
    }
  }

  function reset() {
    token++
    detail.value = ''
  }

  return { detail, examine, reset }
}
