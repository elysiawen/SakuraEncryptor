import { createApp } from 'vue'
import App from './App.vue'
import router from './router.js'
import i18n, { initLocale, listenLocaleChanges } from './i18n.js'
import { initTheme, listenThemeChanges } from './state/theme.js'
import './styles.css'

// Before mount: a light-themed launch must not flash dark chrome first.
initTheme()
listenThemeChanges()
listenLocaleChanges()

// Mount after the first language round-trip. `auto` is resolved by the main
// process (it alone can read the OS locale), and mounting first would paint one
// frame in the wrong language on an English machine.
initLocale().finally(() => {
  createApp(App).use(i18n).use(router).mount('#app')
})
