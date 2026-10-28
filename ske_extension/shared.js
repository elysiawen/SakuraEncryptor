/**
 * Shared state and rule building.
 *
 * Loaded by both the popup (as a <script>) and the background worker (as
 * importScripts), so the rule shape cannot drift between "what the page asked
 * for" and "what the popup shows".
 */

const STORAGE_DOMAINS = 'domains'
const STORAGE_ENABLED = 'enabled'
const STORAGE_PENDING = 'pending'
const STORAGE_AUTO = 'auto'

/** @returns {{ domains: string[], enabled: boolean, auto: boolean }} */
async function readState() {
  const stored = await chrome.storage.local.get([
    STORAGE_DOMAINS,
    STORAGE_ENABLED,
    STORAGE_AUTO,
  ])
  return {
    domains: Array.isArray(stored[STORAGE_DOMAINS]) ? stored[STORAGE_DOMAINS] : [],
    // On by default, which is safe because an empty list matches nothing.
    enabled: stored[STORAGE_ENABLED] !== false,
    // Off by default: it needs an all-sites grant, which the user has to make.
    auto: stored[STORAGE_AUTO] === true,
  }
}

async function writeState(state) {
  await chrome.storage.local.set({
    [STORAGE_DOMAINS]: state.domains,
    [STORAGE_ENABLED]: state.enabled,
    [STORAGE_AUTO]: state.auto === true,
  })
}

/** A drive the page asked for, waiting for the one click that grants access. */
async function readPending() {
  const stored = await chrome.storage.local.get(STORAGE_PENDING)
  return typeof stored[STORAGE_PENDING] === 'string' ? stored[STORAGE_PENDING] : ''
}

async function writePending(domain) {
  await chrome.storage.local.set({ [STORAGE_PENDING]: domain })
  await chrome.action.setBadgeText({ text: domain ? '!' : '' })
  if (domain) await chrome.action.setBadgeBackgroundColor({ color: '#8b5cf6' })
}

/**
 * A rule id per domain, so removal needs no bookkeeping.
 * FNV-1a keeps it stable across restarts; collisions do not matter for a
 * hand-sized list of hosts.
 */
function ruleIdFor(domain) {
  let hash = 0x811c9dc5
  for (let i = 0; i < domain.length; i++) {
    hash ^= domain.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0) % 2147483647 || 1
}

function makeRule(domain) {
  return {
    id: ruleIdFor(domain),
    priority: 1,
    action: {
      type: 'modifyHeaders',
      // Some drives judge a request purely by its CORS headers: `Origin` and
      // `Referer` are what hotlink protection looks at, and neither means
      // anything to a file stream. Dropping them makes the request look like the
      // one a native client would send.
      requestHeaders: [
        { header: 'origin', operation: 'remove' },
        { header: 'referer', operation: 'remove' },
      ],
      responseHeaders: [
        { header: 'access-control-allow-origin', operation: 'set', value: '*' },
        // Only consulted for preflights; harmless everywhere else.
        { header: 'access-control-allow-methods', operation: 'set', value: 'GET, HEAD, OPTIONS' },
        { header: 'access-control-allow-headers', operation: 'set', value: 'range' },
        { header: 'access-control-max-age', operation: 'set', value: '600' },
      ],
    },
    condition: {
      // Scoped by domain only, with no `resourceTypes` filter on purpose: a
      // cross-origin fetch started by a service worker may be reported as
      // "xmlhttprequest", "other" or "fetch", and a filter that guesses wrong
      // matches nothing — silently.
      requestDomains: [domain],
    },
  }
}

/** Rebuild the dynamic rules from the stored list. The rules are the truth. */
async function syncRules() {
  const state = await readState()
  const existing = await chrome.declarativeNetRequest.getDynamicRules()

  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: existing.map((rule) => rule.id),
    addRules: state.enabled ? state.domains.map(makeRule) : [],
  })
}

/** Accepts a pasted URL, a host, or a wildcard; returns a bare hostname. */
function normalizeDomain(raw) {
  const trimmed = String(raw || '').trim().toLowerCase()
  if (!trimmed) throw new Error('请输入域名')

  const withoutScheme = trimmed.replace(/^[a-z]+:\/\//, '')
  const host = withoutScheme.split('/')[0].split(':')[0].replace(/^\*\./, '')
  if (!/^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/.test(host)) {
    throw new Error('域名格式不正确')
  }
  return host
}

function originPattern(domain) {
  return `*://*.${domain}/*`
}

/**
 * Whether an installed rule would fire for a request on [domain].
 *
 * `testMatchOutcome` answers with the actual rule engine rather than our own
 * bookkeeping, which is the only way to tell "the rule is broken" apart from
 * "the rule never matched this request".
 *
 * @returns rule count, or -1 when the API refuses.
 */
async function countMatches(domain) {
  try {
    const { matchedRules } = await chrome.declarativeNetRequest.testMatchOutcome({
      url: `https://${domain}/probe.ske`,
      type: 'xmlhttprequest',
    })
    return matchedRules.length
  } catch {
    return -1
  }
}
