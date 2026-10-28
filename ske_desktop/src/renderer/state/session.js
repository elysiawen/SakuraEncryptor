/**
 * Module-level app state.
 *
 * A single reactive store rather than Pinia: the surface is small, and the
 * existing web client already uses this pattern, so the logic layer can be
 * shared later without rewriting state plumbing.
 */
import { computed, reactive } from 'vue'

const api = window.ske
let toastSeq = 0

export const session = reactive({
  ready: false,
  bootError: '',
  info: null,
  // `factors` and `match` come from the main process: the renderer only lays
  // out the fields, it never decides which proof is sufficient.
  lock: {
    enabled: false,
    unlocked: true,
    hasPassword: false,
    hasTotp: false,
    factors: [],
    match: 'all',
    lockedUntil: 0,
  },
  profiles: [],
  activeId: null,
  settings: null,
  toasts: [],
})

export const activeProfile = computed(
  () => session.profiles.find((p) => p.id === session.activeId) || null,
)

export const isLocked = computed(() => session.lock.enabled && !session.lock.unlocked)

/** Unwrap the `{ ok, error }` envelope every IPC handler returns. */
async function call(promise) {
  const res = await promise
  if (!res?.ok) throw new Error(res?.error || '操作失败')
  return res
}

function requireApi() {
  if (!api) throw new Error('preload 桥不可用，请通过 Electron 启动')
  return api
}

export function toast(message, kind = 'info') {
  const id = ++toastSeq
  session.toasts.push({ id, message, kind })
  setTimeout(() => {
    const index = session.toasts.findIndex((t) => t.id === id)
    if (index >= 0) session.toasts.splice(index, 1)
  }, kind === 'error' ? 6000 : 3200)
}

export function dismissToast(id) {
  const index = session.toasts.findIndex((t) => t.id === id)
  if (index >= 0) session.toasts.splice(index, 1)
}

/** Run an action, surfacing any failure as a toast and returning null. */
export async function attempt(fn, { success } = {}) {
  try {
    const result = await fn()
    if (success) toast(success, 'ok')
    return result
  } catch (err) {
    toast(err.message, 'error')
    return null
  }
}

export async function bootstrap() {
  if (!api) {
    session.bootError = 'preload 桥不可用：请通过桌面应用启动，而不是直接打开网页。'
    session.ready = true
    return
  }

  try {
    const [info, lockState, vault, settings] = await Promise.all([
      call(api.app.info()),
      call(api.security.state()),
      call(api.vault.list()),
      call(api.settings.get()),
    ])

    session.info = info
    session.lock = lockState.state
    session.profiles = vault.profiles
    session.activeId = vault.activeId
    session.settings = settings.settings
  } catch (err) {
    session.bootError = err.message
  } finally {
    session.ready = true
  }
}

/**
 * @param {{ password?: string, totp?: string }} input
 *
 * Both fields are sent when the user filled both; the main process ignores the
 * ones the gate does not demand and applies the configured `match` rule.
 */
export async function unlock(input) {
  const res = await call(requireApi().security.unlock(input))
  session.lock = res.state
}

export async function lock() {
  const res = await call(requireApi().security.lock())
  session.lock = res.state
}

export async function setLockPassword(password, currentPassword) {
  const res = await call(requireApi().security.setPassword(password, currentPassword))
  session.lock = res.state
}

/** Turn the lock off; every configured factor has to be supplied. */
export async function disableLock(input) {
  const res = await call(requireApi().security.disable(input))
  session.lock = res.state
}

/** Propose a fresh token secret. Nothing is stored until `confirmTotp`. */
export async function beginTotp() {
  const res = await call(requireApi().security.totp.begin())
  return { secret: res.secret, uri: res.uri }
}

export async function confirmTotp(code, secret) {
  const res = await call(requireApi().security.totp.confirm({ code, secret }))
  session.lock = res.state
  return res.state
}

export async function unbindTotp() {
  const res = await call(requireApi().security.totp.unbind())
  session.lock = res.state
}

/** `all` demands every configured factor, `any` accepts a single one. */
export async function setLockMatch(match) {
  const res = await call(requireApi().security.setMatch(match))
  session.lock = res.state
}

export async function refreshVault() {
  const res = await call(requireApi().vault.list())
  session.profiles = res.profiles
  session.activeId = res.activeId
}

export async function saveProfile(profile) {
  const res = await call(requireApi().vault.save(profile))
  session.profiles = res.profiles
  session.activeId = res.activeId
  return res.profile
}

export async function removeProfile(id) {
  const res = await call(requireApi().vault.remove(id))
  session.profiles = res.profiles
  session.activeId = res.activeId
}

export async function activateProfile(id) {
  const res = await call(requireApi().vault.activate(id))
  session.profiles = res.profiles
  session.activeId = res.activeId
}

export async function patchSettings(patch) {
  const res = await call(requireApi().settings.patch(patch))
  session.settings = res.settings
  return res.settings
}
