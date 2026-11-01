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
import { t } from '../i18n/index.js'

/** Last refusal, as received: { reason, status, origin, url, canAdd }. */
const block = ref(null)
const bannerOpen = ref(false)
/** Key of what the browser helper answered, translated when rendered. */
const noteKey = ref('')
/** In-flight `SUGGEST_DOMAIN`, so the button cannot be double-fired. */
const adding = ref(false)
let installed = false

/**
 * The wording is built at render time, not when the refusal arrives: the banner
 * is a computed value, so switching language re-translates an open banner
 * instead of leaving yesterday's language on screen.
 */
function describe(data) {
  const reason = ['cors', 'forbidden', 'offline'].includes(data?.reason) ? data.reason : 'cors'
  // Only a CORS refusal can be fixed by whitelisting the host, and only a
  // helper that answers makes offering that meaningful.
  const hasHelper = reason === 'cors' && helperAvailable()
  return {
    ...data,
    reason,
    hasHelper,
    canAdd: hasHelper,
  }
}

function bannerText(info) {
  const origin = info.origin || t('upstream.thisDrive')
  const status = info.status
  switch (info.reason) {
    case 'forbidden':
      return {
        title: t('upstream.forbiddenTitle'),
        message: t('upstream.forbiddenText', { origin: info.origin || t('upstream.directLink'), status }),
      }
    case 'offline':
      return { title: t('upstream.offlineTitle'), message: t('upstream.offlineText') }
    default:
      return {
        title: t('upstream.corsTitle'),
        message: t(
          info.hasHelper ? 'upstream.corsTextWithHelper' : 'upstream.corsTextNoHelper',
          { origin },
        ),
      }
  }
}

/** Hand the refused drive to the helper, on the user's click. */
async function addDrive() {
  const origin = block.value?.origin
  if (!origin || adding.value) return

  adding.value = true
  const reply = await suggestDrive(origin)
  adding.value = false

  noteKey.value = reply
    ? reply.auto
      ? 'upstream.addedAuto'
      : 'upstream.addedManual'
    : 'upstream.noResponse'
}

function install() {
  if (installed || typeof navigator === 'undefined' || !navigator.serviceWorker) return
  installed = true

  navigator.serviceWorker.addEventListener('message', (event) => {
    const data = event.data
    if (data?.type !== 'UPSTREAM_BLOCKED') return

    block.value = describe(data)
    bannerOpen.value = true
    noteKey.value = ''
    adding.value = false
  })
}

export function useUpstreamAlerts() {
  install()

  return {
    /** The banner's payload, or null when it is closed / nothing happened. */
    banner: computed(() => {
      if (!bannerOpen.value || !block.value) return null
      return {
        ...block.value,
        ...bannerText(block.value),
        link: block.value.reason === 'cors' && !block.value.hasHelper ? RELEASES_URL : '',
        note: noteKey.value ? t(noteKey.value) : '',
      }
    }),

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
