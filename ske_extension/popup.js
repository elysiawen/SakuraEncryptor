/**
 * Popup — the control panel.
 *
 * State and rule building live in shared.js, because the background worker uses
 * the same code when the page suggests a drive automatically.
 */

const el = {
  form: document.getElementById('add-form'),
  domain: document.getElementById('domain'),
  add: document.getElementById('add'),
  list: document.getElementById('list'),
  enabled: document.getElementById('enabled'),
  status: document.getElementById('status'),
  origin: document.getElementById('origin'),
  pending: document.getElementById('pending'),
  auto: document.getElementById('auto'),
}

function setStatus(text, tone = '') {
  el.status.textContent = text
  el.status.className = `status${tone ? ' ' + tone : ''}`
}

/**
 * Origin of the tab the popup was opened from — i.e. the Sakura Encryptor page.
 *
 * `declarativeNetRequestWithHostAccess` gates a rule on host access to **both**
 * the request URL and its initiator. Asking only for the drive leaves the rule
 * installed but inert: `testMatchOutcome` still reports a match, because it
 * ignores permissions, and nothing happens on the real request.
 */
async function activePageOrigin() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true })
    if (!tab?.url || !/^https?:/i.test(tab.url)) return ''
    return new URL(tab.url).origin
  } catch {
    return ''
  }
}

async function render() {
  const state = await readState()
  const pending = await readPending()
  const appOrigin = await activePageOrigin()

  // The permission, not the stored flag, is the truth. Chrome closes the popup
  // while its prompt is on screen, so a grant made from the 「自动添加」toggle
  // can land without the click that asked for it ever writing `auto: true`.
  // Left alone, the box renders off while the grant exists, and every rule the
  // background then adds is judged against a flag that disagrees with reality.
  if (!state.auto && (await chrome.permissions.contains({ origins: ['*://*/*'] }))) {
    await writeState({ ...state, auto: true })
    state.auto = true
  }

  el.enabled.checked = state.enabled
  el.auto.checked = state.auto
  el.origin.textContent = appOrigin || '未获取到（请在应用页面打开）'

  if (pending && !state.domains.includes(pending)) {
    // Chrome kills the panel while the permission prompt is up, so the click
    // that asks for the grant cannot be the one that saves the domain — the
    // background's `permissions.onAdded` does that instead. So: one click, one
    // 「允许」, done. The granted branch is only left for a grant that predates
    // the domain (upgrade, or a prompt dismissed then allowed elsewhere).
    const needed = [originPattern(pending)]
    if (appOrigin) needed.push(`${appOrigin}/*`)
    const granted = await chrome.permissions.contains({ origins: needed })

    el.pending.hidden = false
    el.pending.textContent = granted
      ? `已获得 ${pending} 的访问权限，点「添加」即可完成。`
      : `检测到被跨域拦截的网盘 ${pending}。点「添加」并在弹框中点「允许」就会自动加入，` +
        '本面板被关闭不用管；回到网页刷新即可解密。'
    el.domain.value = pending
  } else {
    el.pending.hidden = true
  }

  el.list.textContent = ''

  if (state.domains.length === 0) {
    const empty = document.createElement('li')
    empty.className = 'empty'
    empty.textContent = '还没有添加域名'
    el.list.append(empty)
  }

  let unmatched = 0

  for (const domain of state.domains) {
    const row = document.createElement('li')

    const name = document.createElement('span')
    name.textContent = domain

    const matches = await countMatches(domain)
    if (matches === 0 && state.enabled) unmatched++

    const badge = document.createElement('span')
    badge.className = `badge ${matches > 0 ? 'ok' : matches === 0 ? 'bad' : 'unknown'}`
    badge.textContent = matches > 0 ? '规则命中' : matches === 0 ? '未命中' : '无法自检'

    const remove = document.createElement('button')
    remove.type = 'button'
    remove.className = 'remove'
    remove.textContent = '移除'
    remove.addEventListener('click', () => removeDomain(domain))

    row.append(name, badge, remove)
    el.list.append(row)
  }

  if (!state.enabled) {
    setStatus('已暂停', 'off')
  } else if (unmatched > 0) {
    setStatus('规则未命中，请重新添加域名', 'error')
  } else {
    setStatus('规则已生效', 'on')
  }
}

async function addDomain(raw) {
  const domain = normalizeDomain(raw)

  const appOrigin = await activePageOrigin()
  if (!appOrigin) {
    throw new Error('请先在 Sakura Encryptor 网页上打开本插件，再添加域名')
  }

  // Recorded before the prompt, because this document will not survive it:
  // Chrome closes the panel while the prompt is up, so everything after
  // `request()` here may never run. `permissions.onAdded` in the background
  // picks the domain back up and finishes the job — that is what turns
  // 「添加」+ «允许» into one step instead of two.
  await writePending(domain)

  // Asked for with no further `await` in between: Chrome only grants the prompt
  // while the click's user activation is still alive. Re-prompting for an
  // already granted origin is a silent no-op, so the duplicate check can wait.
  // Both origins go into one prompt: the drive's response and the page that
  // reads it.
  let granted = false
  try {
    granted = await chrome.permissions.request({
      origins: [originPattern(domain), `${appOrigin}/*`],
    })
  } catch (err) {
    await writePending('')
    throw err
  }

  if (!granted) {
    await writePending('')
    throw new Error('没有获得域名或页面来源的访问权限')
  }

  const state = await readState()
  if (!state.domains.includes(domain)) {
    await writeState({ ...state, domains: [...state.domains, domain].sort() })
  }

  await syncRules()
  await writePending('')
  return domain
}

async function removeDomain(domain) {
  const state = await readState()
  const next = { ...state, domains: state.domains.filter((item) => item !== domain) }

  await writeState(next)
  await syncRules()

  // Keep the permission only if another rule still covers that origin.
  const stillUsed = next.domains.some((item) => domain === item || domain.endsWith(`.${item}`))
  if (!stillUsed) {
    await chrome.permissions.remove({ origins: [originPattern(domain)] }).catch(() => {})
  }

  await render()
}

el.form.addEventListener('submit', async (event) => {
  event.preventDefault()
  el.add.disabled = true
  try {
    const domain = await addDomain(el.domain.value)
    el.domain.value = ''
    await render()
    setStatus(`已生效：${domain}`, 'on')
  } catch (err) {
    setStatus(err.message || '添加失败', 'error')
  } finally {
    el.add.disabled = false
  }
})

el.auto.addEventListener('change', async () => {
  const state = await readState()

  if (el.auto.checked) {
    // Must run inside this event: the prompt needs the click's user activation.
    const granted = await chrome.permissions.request({ origins: ['*://*/*'] })
    if (!granted) {
      el.auto.checked = false
      setStatus('没有获得全站权限，仍按域名逐个授权', 'off')
      return
    }
    // Rules added while the grant was missing were inert; rebuilding them here
    // means the toggle takes effect on the spot instead of on the next open.
    await writeState({ ...state, auto: true })
    await syncRules()
    await render()
    setStatus('自动模式已开启', 'on')
    return
  }

  // Domains that were added silently only work through the all-sites grant, and
  // `testMatchOutcome` cannot tell that they would stop firing. Convert them to
  // per-domain grants in one prompt instead of quietly breaking them.
  const needed = state.domains.map(originPattern)
  const appOrigin = await activePageOrigin()
  if (appOrigin) needed.push(`${appOrigin}/*`)

  const converted = needed.length === 0 || (await chrome.permissions.request({ origins: needed }))
  if (!converted) {
    el.auto.checked = true
    setStatus('未授权逐个域名，保持自动模式', 'off')
    return
  }

  await writeState({ ...state, auto: false })
  await chrome.permissions.remove({ origins: ['*://*/*'] }).catch(() => {})

  // If Chrome kept the grant, the flag has to follow it: `render()` trusts the
  // permission, so leaving them split would flip the box back with no reason.
  if (await chrome.permissions.contains({ origins: ['*://*/*'] })) {
    await writeState({ ...state, auto: true })
    el.auto.checked = true
    setStatus('没能撤销全站权限，仍保持自动模式', 'error')
    return
  }

  await render()
  setStatus('已转为逐个域名授权', 'on')
})

el.enabled.addEventListener('change', async () => {
  const state = await readState()
  await writeState({ ...state, enabled: el.enabled.checked })
  await syncRules()
  await render()
})

// Rebuilt on open rather than only on change: a reloaded extension keeps its
// dynamic rules, and rules written by an older version would silently keep the
// old behaviour.
syncRules()
  .then(render)
  .catch((err) => setStatus(err.message || '读取状态失败', 'error'))
