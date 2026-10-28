/**
 * useExtensionBridge — hand a blocked drive to the browser helper.
 *
 * The helper extension injects the missing `Access-Control-Allow-Origin`; making
 * the user retype a hostname they only ever saw in an error message is the kind
 * of step people skip. So when a CORS refusal is diagnosed, the origin is pushed
 * to the extension.
 *
 * Chrome exposes `chrome.runtime` to a page only when the extension lists that
 * origin in `externally_connectable`, so an uninstalled (or non-matching) helper
 * simply makes these calls no-ops — nothing to feature-detect beyond that.
 *
 * The ID is fixed by the `key` field in the extension manifest, which is what
 * lets a page address it at all.
 */

export const EXTENSION_ID = 'bladkclndpeaknhmngaoabdpalapdmgg'

/** Latest reply from the helper, for UI that wants to comment on it. */
export const lastReply = { value: null }

/**
 * Whether a helper is reachable from this page.
 *
 * Exported so copy can branch on it: "是否将域名添加到插件" only makes sense
 * when there is a plugin to add it to.
 */
export function helperAvailable() {
  try {
    // `chrome.runtime.id` belongs to the extension's own context and is not set
    // on a web page even when messaging is allowed, so it cannot be used as a
    // presence check — `sendMessage` being a function is what identifies the
    // externally-connectable bridge.
    return typeof chrome !== 'undefined' && typeof chrome.runtime?.sendMessage === 'function'
  } catch {
    return false
  }
}

function hostOf(origin) {
  try {
    return new URL(origin).hostname
  } catch {
    return ''
  }
}

/**
 * Ask the helper to unblock [origin].
 *
 * @returns {Promise<{ok: boolean, auto?: boolean, needsPermission?: boolean} | null>}
 *          null when no helper is installed.
 */
export function suggestDrive(origin) {
  const domain = hostOf(origin)
  if (!domain) return Promise.resolve(null)

  if (!helperAvailable()) {
    console.info('[SKE] no browser helper on this origin — install it to unblock CORS drives')
    return Promise.resolve(null)
  }

  return new Promise((resolve) => {
    try {
      chrome.runtime.sendMessage(EXTENSION_ID, { type: 'SUGGEST_DOMAIN', domain }, (reply) => {
        // Reading lastError is what suppresses the "no receiving end" warning
        // when the extension was uninstalled or updated.
        const failed = chrome.runtime.lastError
        if (failed) {
          // Usually a stale ID: the manifest `key` fixes it, but an install made
          // before that key existed has a different one.
          console.warn('[SKE] helper did not answer:', failed.message, `(id ${EXTENSION_ID})`)
          resolve(null)
          return
        }
        console.info('[SKE] helper reply:', reply)
        lastReply.value = reply || null
        resolve(lastReply.value)
      })
    } catch (err) {
      console.warn('[SKE] helper call threw:', err)
      resolve(null)
    }
  })
}
