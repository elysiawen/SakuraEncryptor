/**
 * Locale metadata shared by the main process and the renderer.
 *
 * Both sides need the same table: the settings card offers it, and the main
 * process resolves `auto` against the OS locale to rebuild the tray. Keeping it
 * in one module is what stops the two halves from drifting apart.
 */

/**
 * Locales with a translation table. `label` is the endonym on purpose — a list
 * of languages is the one place where translating the names is a bug.
 */
export const LOCALES = [
  { code: 'zh-CN', label: '简体中文' },
  { code: 'en-US', label: 'English' },
]

/** Fallback when the active locale is missing a key, and when nothing else matches. */
export const DEFAULT_LOCALE = 'zh-CN'

/** Stored in settings.json when the UI should follow the OS language. */
export const LANGUAGE_AUTO = 'auto'

export const localeCodes = LOCALES.map((entry) => entry.code)

/**
 * Map any BCP-47-ish tag onto one of the shipped locales.
 *
 * `zh`, `zh-Hans`, `zh_CN` all become `zh-CN`; `en-GB` becomes `en-US`. An
 * unsupported language (`ja`, `de`) returns an empty string so callers fall back
 * deliberately rather than silently showing the wrong table.
 */
export function normalizeLocale(value) {
  if (typeof value !== 'string') return ''
  const tag = value.trim().replace(/_/g, '-').toLowerCase()
  if (!tag) return ''
  const exact = localeCodes.find((code) => code.toLowerCase() === tag)
  if (exact) return exact
  const base = tag.split('-')[0]
  return localeCodes.find((code) => code.toLowerCase().split('-')[0] === base) || ''
}

export function isLocale(value) {
  return !!normalizeLocale(value)
}

/**
 * Turn a stored `language` setting into a concrete locale.
 *
 * `auto` follows the OS: Electron reports it through `app.getLocale()` in main
 * and `navigator.language` in the renderer. Both paths end up here so the two
 * processes cannot disagree about what `auto` means.
 *
 * @param {string} language  stored setting: 'auto' or a locale code
 * @param {string} [systemLocale]  the OS locale
 */
export function resolveLocale(language, systemLocale) {
  if (language === LANGUAGE_AUTO || language == null || language === '') {
    return normalizeLocale(systemLocale) || DEFAULT_LOCALE
  }
  return normalizeLocale(language) || normalizeLocale(systemLocale) || DEFAULT_LOCALE
}
