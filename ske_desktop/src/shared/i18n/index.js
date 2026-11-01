/**
 * Message tables, shared verbatim by both processes.
 *
 * The translations are plain ES modules rather than JSON so that Node's ESM
 * loader (main process) and Vite (renderer) can `import` the exact same files.
 * A JSON copy per side would be two places to keep in sync — which is how a
 * string ends up translated in one window and not in the other.
 *
 * Adding a language = one import here + one entry in `LOCALES`.
 */
import zhCN from './locales/zh-CN/index.js'
import enUS from './locales/en-US/index.js'

export const MESSAGES = {
  'zh-CN': zhCN,
  'en-US': enUS,
}

export * from './config.js'
