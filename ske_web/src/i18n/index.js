/**
 * i18n — one instance for the whole app.
 *
 * Templates use `$t` (global injection); plain modules (composables, service
 * helpers) import `t` from here, so a message never has to be duplicated.
 */
import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN.js'
import enUS from './locales/en-US.js'

export const SUPPORTED_LOCALES = [
  { value: 'zh-CN', label: '中文' },
  { value: 'en-US', label: 'English' },
]

export const DEFAULT_LOCALE = 'zh-CN'
const STORAGE_KEY = 'ske_locale'

function storedLocale() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return SUPPORTED_LOCALES.some((item) => item.value === saved) ? saved : ''
  } catch {
    return ''
  }
}

/** Explicit choice first, then the browser language, then Chinese. */
function detectLocale() {
  const saved = storedLocale()
  if (saved) return saved
  const nav = (navigator.language || DEFAULT_LOCALE).toLowerCase()
  if (nav.startsWith('zh')) return 'zh-CN'
  if (nav.startsWith('en')) return 'en-US'
  return DEFAULT_LOCALE
}

export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: typeof window === 'undefined' ? DEFAULT_LOCALE : detectLocale(),
  fallbackLocale: DEFAULT_LOCALE,
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
})

const LOCALE_FALLBACKS = {
  zh: 'zh-CN',
  'zh-cn': 'zh-CN',
  'zh-hans': 'zh-CN',
  'zh-sg': 'zh-CN',
  'zh-tw': 'zh-CN',
  'zh-hant': 'zh-CN',
  'zh-hk': 'zh-CN',
  en: 'en-US',
  'en-us': 'en-US',
  'en-gb': 'en-US',
}

/** BCP-47-ish browser tags -> one of our two locales. */
export function normalizeLocale(tag) {
  if (!tag) return DEFAULT_LOCALE
  const lower = String(tag).toLowerCase()
  if (SUPPORTED_LOCALES.some((item) => item.value.toLowerCase() === lower)) {
    return SUPPORTED_LOCALES.find((item) => item.value.toLowerCase() === lower).value
  }
  return LOCALE_FALLBACKS[lower] || LOCALE_FALLBACKS[lower.split('-')[0]] || DEFAULT_LOCALE
}

export function getLocale() {
  return i18n.global.locale.value
}

export function setLocale(locale) {
  const next = normalizeLocale(locale)
  i18n.global.locale.value = next
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // Private mode: the choice simply does not persist.
  }
  if (typeof document !== 'undefined') document.documentElement.lang = next
}

/** `t` for modules outside a component setup (composables, stores). */
export function t(key, named) {
  return named ? i18n.global.t(key, named) : i18n.global.t(key)
}

/** Whether the active locale is Chinese — used where wording differs by grammar. */
export function isZh() {
  return getLocale().startsWith('zh')
}

if (typeof document !== 'undefined') document.documentElement.lang = getLocale()
