/**
 * Renderer-side i18n.
 *
 * `vue-i18n` is confined to this module: components use `$t` in templates
 * (global injection) or `t()` imported from here, never the library itself.
 * That keeps a future library swap — or a bump that breaks an API — to a single
 * file, which is the part that actually matters for maintenance.
 *
 * Locale state has two sources, exactly like the theme: `localStorage` paints
 * the first frame in the right language, `settings.json` is authoritative. The
 * player window boots from the same cache, so both windows agree before either
 * has talked to the main process.
 */
import { createI18n } from 'vue-i18n'
import {
  MESSAGES,
  DEFAULT_LOCALE,
  LANGUAGE_AUTO,
  isLocale,
  normalizeLocale,
  resolveLocale,
} from '../shared/i18n/index.js'
import { patchSettings, session } from './state/session.js'

const CACHE_KEY = 'ske.locale'

/** First run has no cache: the OS locale is the best guess, and main confirms it. */
const initial = normalizeLocale(readCache()) || resolveLocale(LANGUAGE_AUTO, navigator.language)

const i18n = createI18n({
  // Composition API mode. The legacy API would give every `<script setup>`
  // component a different `$t` contract than the one in the docs.
  legacy: false,
  // Exposes `$t` to every template, so converting a component costs no imports.
  globalInjection: true,
  locale: initial,
  fallbackLocale: DEFAULT_LOCALE,
  messages: MESSAGES,
  // A missing key during migration should be visible in development only; in a
  // shipped build it is a fallback, not a console flood.
  missingWarn: !!import.meta.env?.DEV,
  fallbackWarn: false,
})

/** The plugin instance handed to `app.use()`. */
export default i18n

/** Writable ref; the template re-renders when it changes. */
export const locale = i18n.global.locale

export const t = (key, params) => i18n.global.t(key, params ?? {})

/** Whether a key exists — for optional labels rather than for control flow. */
export const te = (key) => i18n.global.te(key)

function readCache() {
  try {
    return localStorage.getItem(CACHE_KEY) || ''
  } catch {
    return ''
  }
}

/** Apply a concrete locale everywhere it is visible outside Vue (CSS, a11y, cache). */
function paint(code) {
  const value = normalizeLocale(code) || DEFAULT_LOCALE
  locale.value = value
  document.documentElement.lang = value
  try {
    localStorage.setItem(CACHE_KEY, value)
  } catch {
    // A disabled storage backend only costs one frame of flash on next start.
  }
  return value
}

/** What the settings card should show: 'auto' or a concrete code. */
export function languageChoice() {
  const stored = session.settings?.language
  return stored === LANGUAGE_AUTO || isLocale(stored) ? stored : LANGUAGE_AUTO
}

/**
 * Boot: paint from cache, then trust the persisted setting.
 *
 * The main process resolves `auto` with `app.getLocale()`, which is the
 * authority on the OS language — the renderer's `navigator.language` only fills
 * the gap before the first IPC round-trip.
 */
export async function initLocale() {
  paint(initial)
  let res = null
  try {
    res = await window.ske?.settings?.get?.()
  } catch {
    // No bridge (a page opened outside Electron): the cached locale stands.
  }
  if (res?.ok) {
    session.settings = res.settings
    if (res.locale) paint(res.locale)
  }
  return locale.value
}

/** Keep every other window (the player) in step with this one. */
export function listenLocaleChanges() {
  window.ske?.locale?.onChange?.((code) => paint(code))
}

/**
 * Switch the interface language.
 *
 * Same three steps as the theme toggle: repaint now, persist, then tell the
 * other windows. `patchSettings` updates the main process too, which is what
 * rebuilds the tray menu in the new language.
 *
 * @param {string} choice  'auto' | 'zh-CN' | 'en-US'
 */
export async function setLocale(choice) {
  // Optimistic: the dropdown must not wait on a disk write.
  paint(choice === LANGUAGE_AUTO ? resolveLocale(LANGUAGE_AUTO, navigator.language) : choice)

  try {
    const saved = await patchSettings({ language: choice })
    // Main's answer wins for `auto`: only it can read the OS locale reliably.
    if (saved?.locale) paint(saved.locale)
    await window.ske?.locale?.broadcast?.(choice)
  } catch {
    // The UI already switched; a failed write only means it reverts next launch.
  }
  return locale.value
}
