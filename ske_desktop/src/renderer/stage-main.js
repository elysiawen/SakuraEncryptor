// Separate entry for the player window.
//
// It intentionally mounts no router and no shell: a video window should be a
// video window, and the app's global shortcuts must not fire while the user is
// typing in the main window.
import { createApp } from 'vue'
import StageView from './components/StageView.vue'
import i18n, { initLocale, listenLocaleChanges } from './i18n.js'
import { initTheme, listenThemeChanges } from './state/theme.js'
import './styles.css'

// Same theme and language as the shell; also fills session.settings for the
// volume defaults.
initTheme()
listenThemeChanges()
listenLocaleChanges()

initLocale().finally(() => {
  createApp(StageView).use(i18n).mount('#app')
})
