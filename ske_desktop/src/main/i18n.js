/**
 * Main-process translations.
 *
 * The renderer owns the Vue binding; main only needs a lookup so the tray menu
 * and native file dialogs can follow the same language. Both read the same
 * message table through `@intlify/core-base`, vue-i18n's framework-free core —
 * there is no second copy of the strings to keep in sync.
 *
 * No reactivity here on purpose: a locale switch is an event, handled by
 * rebuilding the things that baked the old language into native objects (the
 * tray context menu). Everything else is asked for at call time.
 */
import {
  compile,
  createCoreContext,
  registerMessageCompiler,
  registerMessageResolver,
  resolveValue,
  translate,
} from '@intlify/core-base'
import { MESSAGES, DEFAULT_LOCALE, normalizeLocale, resolveLocale } from '../shared/i18n/index.js'

// The bare core ships neither piece installed, because its defaults are the
// cheapest possible ones: a key is looked up literally, and a message is
// returned verbatim. vue-i18n's plugin registers both on install, so a
// main-process use of the core has to do it once itself — otherwise
// `t('tray.quit')` never resolves and `{count}` is printed as-is.
registerMessageCompiler(compile)
registerMessageResolver(resolveValue)

const context = createCoreContext({
  locale: DEFAULT_LOCALE,
  fallbackLocale: DEFAULT_LOCALE,
  messages: MESSAGES,
  // A missing key returns the key here. The audit script is what catches it —
  // a tray that flashes a key name is bad, a console flooded per repaint worse.
  missingWarn: false,
  fallbackWarn: false,
})

/** The concrete locale in effect ('zh-CN' | 'en-US'), never 'auto'. */
export function currentLocale() {
  return normalizeLocale(context.locale) || DEFAULT_LOCALE
}

/**
 * @param {string} language  the stored setting, `auto` included
 * @param {string} [systemLocale]  Electron's `app.getLocale()`
 * @returns {string} the concrete locale that is now in effect
 */
export function applyLanguage(language, systemLocale) {
  context.locale = resolveLocale(language, systemLocale)
  return context.locale
}

/**
 * @param {string} key  dot path, e.g. `tray.quit`
 * @param {Record<string, unknown>} [params]  ICU placeholders
 */
export function t(key, params) {
  return translate(context, key, params ?? {})
}
