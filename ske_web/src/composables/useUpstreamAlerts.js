/**
 * useUpstreamAlerts — explain hosts the browser is not allowed to read.
 *
 * The worker cannot show UI, so when an upstream fetch is refused (a missing
 * `Access-Control-Allow-Origin`, a 401/403 from hotlink protection, or a dead
 * network) it posts `UPSTREAM_BLOCKED` to every window. Without that the user
 * only sees "playback failed" over and over, with no hint that retrying can
 * never work — the bytes stay unreachable until the helper extension grants
 * that drive's CORS, or the user opens the file outside the browser.
 *
 * Two consumers, one source:
 *   - the global banner (thumbnails, downloads, anything with no error surface)
 *   - nothing else — player and viewer error pages run their own diagnosis
 *     (see useDecryptDiagnosis) and just hide the banner so it is not repeated.
 *
 * When a helper answers, the banner asks ("是否将域名添加到插件") and adds the
 * drive only on click: widening a drive's CORS is the user's call, not a side
 * effect of a failed thumbnail. The error panels have no button, so they hand
 * the drive over themselves and append whatever the helper answered.
 *
 * Kept as a module singleton: the state is global, and several components can
 * call this without each installing its own listener.
 */
import { computed, ref } from 'vue'
import { helperAvailable, suggestDrive } from './useExtensionBridge.js'
import { RELEASES_URL } from './useProjectLinks.js'

/** Last refusal: { reason, status, origin, url, title, message, link, canAdd }. */
const block = ref(null)
const bannerOpen = ref(false)
/** What the browser helper answered, in one sentence. */
const note = ref('')
/** In-flight `SUGGEST_DOMAIN`, so the button cannot be double-fired. */
const adding = ref(false)
let installed = false

const MESSAGES = {
  cors: {
    title: '该网盘的跨域请求被拒绝',
    // The sentence changes with what the user can actually do: ask the helper
    // that is already there, or go get one of the clients.
    text: (origin, _status, hasHelper) =>
      `浏览器直连 ${origin || '该网盘'} 被跨域策略（CORS）拦截，` +
      (hasHelper
        ? '当前已安装插件，是否将域名添加到插件。'
        : '请使用浏览器插件，或直接下载客户端使用。'),
    /** Clickable line under the message; pointless once the helper is present. */
    link: (hasHelper) => (hasHelper ? '' : RELEASES_URL),
  },
  forbidden: {
    title: '该网盘拒绝了浏览器访问',
    text: (origin, status) =>
      `网盘拒绝了 ${origin || '直链'} 的访问（HTTP ${status}），可能是直链已过期、` +
      '需要登录或开启了防盗链。请改用桌面客户端 / 安卓客户端浏览。',
  },
  offline: {
    title: '网络不可用',
    text: () => '网络已断开，无法读取网盘上的加密文件。请检查网络后重试。',
  },
}

function describe(data) {
  const entry = MESSAGES[data.reason] || MESSAGES.cors
  // Only a CORS refusal can be fixed by whitelisting the host, and only a
  // helper that answers makes offering that meaningful.
  const hasHelper = entry === MESSAGES.cors && helperAvailable()
  return {
    ...data,
    title: entry.title,
    message: entry.text(data.origin, data.status, hasHelper),
    link: typeof entry.link === 'function' ? entry.link(hasHelper) : entry.link || '',
    canAdd: hasHelper,
  }
}

/** Hand the refused drive to the helper, on the user's click. */
async function addDrive() {
  const origin = block.value?.origin
  if (!origin || adding.value) return

  adding.value = true
  const reply = await suggestDrive(origin)
  adding.value = false

  note.value = reply
    ? reply.auto
      ? '已自动加入跨域小助手，刷新页面即可解密。'
      : '已在跨域小助手中登记：打开插件点「添加」并允许授权，授权成功即自动加入' +
        '（面板可能被关闭），回到页面刷新就能解密。'
    : '插件没有响应，请打开插件手动添加该域名。'
}

function install() {
  if (installed || typeof navigator === 'undefined' || !navigator.serviceWorker) return
  installed = true

  navigator.serviceWorker.addEventListener('message', (event) => {
    const data = event.data
    if (data?.type !== 'UPSTREAM_BLOCKED') return

    block.value = describe(data)
    bannerOpen.value = true
    note.value = ''
    adding.value = false
  })
}

export function useUpstreamAlerts() {
  install()

  return {
    /** The banner's payload, or null when it is closed / nothing happened. */
    banner: computed(() => (bannerOpen.value && block.value ? { ...block.value, note: note.value } : null)),

    /** Close the banner without forgetting the reason. */
    dismissBanner: () => {
      bannerOpen.value = false
    },

    /** True while `SUGGEST_DOMAIN` is in flight. */
    adding,

    /** Whitelist the refused drive — the banner's confirm action. */
    addDrive,
  }
}
