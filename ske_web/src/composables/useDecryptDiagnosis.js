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
import { computed, ref } from 'vue'
import { helperAvailable, suggestDrive } from './useExtensionBridge.js'
import { RELEASES_URL } from './useProjectLinks.js'
import { t } from '../i18n/index.js'

/** Origin of the drive behind a decrypt-proxy URL; '' for local vault files. */
function upstreamOrigin(proxyUrl) {
  try {
    const target = new URL(proxyUrl, location.href).searchParams.get('url')
    return target ? new URL(target).origin : ''
  } catch {
    return ''
  }
}

/** Code -> message key; one cause, one sentence. */
const TEXT_KEYS = {
  NO_KEY: 'diagnosis.noKey',
  BAD_PASSWORD: 'diagnosis.badPassword',
  CORRUPT: 'diagnosis.corrupt',
  OK: 'diagnosis.ok',
  UNREACHABLE: 'diagnosis.unreachable',
}

function corsText(origin) {
  const hasHelper = helperAvailable()
  let text = t(
    hasHelper ? 'diagnosis.corsTextWithHelper' : 'diagnosis.corsTextNoHelper',
    { origin: origin || t('upstream.thisDrive') },
  )
  // This panel has no button, so a user without the helper gets the address
  // instead of an instruction to click something that is not there.
  if (!hasHelper) text += '\n' + t('upstream.downloadLink') + RELEASES_URL
  return text
}

/** `title + separator + text` for one upstream refusal reason. */
function upstreamText(reason, origin) {
  switch (reason) {
    case 'forbidden':
      return `${t('diagnosis.forbiddenTitle')}${t('diagnosis.separator')}${t('diagnosis.forbiddenText')}`
    case 'offline':
      return `${t('diagnosis.offlineTitle')}${t('diagnosis.separator')}${t('diagnosis.offlineText')}`
    default:
      return `${t('diagnosis.corsTitle')}${t('diagnosis.separator')}${corsText(origin)}`
  }
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
    const reason = ['cors', 'forbidden', 'offline'].includes(result.reason) ? result.reason : 'cors'
    return upstreamText(reason, origin)
  }

  if (result.code === 'UNKNOWN') {
    // A 404 here means the worker is not intercepting yet: nothing decrypted,
    // nothing to blame on the file.
    if (result.status === 404) return t('diagnosis.proxyNotReady')
    return t('diagnosis.httpFailed', { status: result.status })
  }

  const key = TEXT_KEYS[result.code]
  return key ? t(key) : t('diagnosis.httpFailedShort', { status: result.status })
}

/**
 * Wire a component's failure state to a diagnosis.
 *
 * @param {() => string} getProxyUrl the URL that just failed
 */
export function useFailureDiagnosis(getProxyUrl) {
  // The raw answer and the helper's reply are kept, not the finished sentence:
  // the text is a computed value, so switching language re-translates a panel
  // that is already on screen.
  const result = ref(null)
  const origin = ref('')
  const replyKey = ref('')
  let token = 0

  const detail = computed(() => {
    const text = describeDiagnosis(result.value, origin.value)
    if (!text) return ''
    return replyKey.value ? `${text}\n${t(replyKey.value)}` : text
  })

  async function examine() {
    const url = getProxyUrl()
    if (!url) return

    // A playlist can fail several files in a row; only the newest answer counts.
    const mine = ++token
    result.value = null
    origin.value = ''
    replyKey.value = ''
    const answer = await diagnoseDecrypt(url)
    if (mine !== token) return

    const source = upstreamOrigin(url)
    result.value = answer
    origin.value = source

    if (answer.code === 'UPSTREAM_BLOCKED' && answer.reason === 'cors') {
      // No button in here, so the drive goes to the helper straight away — the
      // user should not have to retype a hostname they only saw in a message —
      // and whatever the helper answered is appended under the cause.
      const reply = await suggestDrive(source)
      if (mine !== token || !reply) return
      replyKey.value = reply.auto ? 'diagnosis.addedAuto' : 'diagnosis.addedManual'
    }
  }

  function reset() {
    token++
    result.value = null
    origin.value = ''
    replyKey.value = ''
  }

  return { detail, examine, reset }
}
