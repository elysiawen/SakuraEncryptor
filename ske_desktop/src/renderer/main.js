import { createApp } from 'vue'
import App from './App.vue'
import router from './router.js'
import { initTheme, listenThemeChanges } from './state/theme.js'
import './styles.css'

// Before mount: a light-themed launch must not flash dark chrome first.
initTheme()
listenThemeChanges()

createApp(App).use(router).mount('#app')

