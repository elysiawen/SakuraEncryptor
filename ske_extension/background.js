/**
 * Background worker — the only context the web app can talk to.
 *
 * A page can never grant itself host access, so the flow adapts to what is
 * already allowed:
 *
 *   - permission already granted → add the rule right away, no UI at all
 *   - not granted yet           → try the prompt here (a click in the page may
 *                                 still carry its activation); if that is
 *                                 refused, remember the domain, badge the icon
 *                                 and let the popup ask for the click
 *
 * Either way the user is not expected to retype a hostname they just saw in an
 * error message.
 */

importScripts('shared.js')

/**
 * Finish whatever the popup could not.
 *
 * Chrome tears the panel down while its permission prompt is on screen, so the
 * click that asked for a grant never reaches the code after `request()`: the
 * document that would have saved the domain is already gone. The grant event
 * outlives it, so it is the only place that can see 「允许」 actually happened —
 * and doing the work here is what makes one click enough.
 *
 * Two ways a grant arrives:
 *   - the all-sites origin from 「自动添加检测到的网盘」 → the flag it could not
 *     save
 *   - a drive's own origin from the popup's 「添加」 prompt → the domain it could
 *     not save, still sitting in `pending`
 *
 * (Do not write the all-sites pattern into a block comment: its tail closes the
 * comment early — the parser then trips over the lines that follow.)
 */
chrome.permissions.onAdded.addListener(async ({ origins }) => {
  if (!origins) return

  const allSites = origins.includes('*://*/*')
  const state = await readState()
  const pending = await readPending()
  const covered = pending
    ? await chrome.permissions.contains({ origins: [originPattern(pending)] })
    : false
  const addPending = covered && !state.domains.includes(pending)

  // Neither grant shape: some other permission change, nothing to record.
  if (!allSites && !addPending) return

  await writeState({
    ...state,
    auto: allSites ? true : state.auto,
    domains: addPending ? [...state.domains, pending].sort() : state.domains,
  })
  if (addPending) await writePending('')
  await syncRules()
})

chrome.runtime.onMessageExternal.addListener((message, sender, sendResponse) => {
  // Lets the page (or a console) confirm the ID wiring before blaming the rules.
  if (message?.type === 'PING') {
    sendResponse({ ok: true, version: chrome.runtime.getManifest().version })
    return false
  }

  if (message?.type !== 'SUGGEST_DOMAIN') return false

  handleSuggestion(message.domain, sender.origin)
    .then(sendResponse)
    .catch((err) => sendResponse({ ok: false, error: err?.message || 'failed' }))

  // Keeps the channel open for the async reply.
  return true
})

async function handleSuggestion(raw, senderOrigin) {
  let domain
  try {
    domain = normalizeDomain(raw)
  } catch {
    return { ok: false, error: 'bad domain' }
  }

  const state = await readState()
  if (state.domains.includes(domain)) {
    // Rules are rebuilt on popup open, but a suggestion is a cheap opportunity
    // to make sure an old install still has them.
    await syncRules()
    return { ok: true, auto: true }
  }

  // A rule needs host access to the response domain *and* to the page that reads
  // it; only the second one may still be missing.
  const needed = [originPattern(domain)]
  if (senderOrigin && /^https?:/.test(senderOrigin)) needed.push(`${senderOrigin}/*`)

  // Auto mode is one all-sites grant the user made on purpose, so any drive the
  // page reports can be added without another visit to the popup.
  const granted = state.auto
    ? await chrome.permissions.contains({ origins: ['*://*/*'] })
    : await chrome.permissions.contains({ origins: needed })

  if (!granted) {
    // A click in the page can carry its user activation all the way here, in
    // which case Chrome puts the prompt up immediately and one click is the
    // whole flow. When it does not, the request throws and the popup takes
    // over — the pending branch below serves both cases.
    try {
      if (await chrome.permissions.request({ origins: needed })) {
        await writeState({ ...state, domains: [...state.domains, domain].sort() })
        await syncRules()
        await writePending('')
        return { ok: true, auto: true }
      }
    } catch {
      // No user activation in this context; ask for the click in the popup.
    }

    await writePending(domain)
    return { ok: true, auto: false, needsPermission: true }
  }

  await writeState({ ...state, domains: [...state.domains, domain].sort() })
  await syncRules()
  await writePending('')
  return { ok: true, auto: true }
}
