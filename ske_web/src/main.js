import { createApp } from 'vue'
import App from './App.vue'
import router from './router.js'
import './style.css'

// Register Service Worker
if ('serviceWorker' in navigator) {
    navigator.serviceWorker
        .register('/sw-decrypt.js', { scope: '/' })
        .then((reg) => console.log('[SKE] Service Worker registered:', reg.scope))
        .catch((err) => console.error('[SKE] SW registration failed:', err))

    // Listen for requests from Service Worker to supply password
    navigator.serviceWorker.addEventListener('message', (event) => {
        if (event.data?.type === 'REQUEST_PASSWORD') {
            const pwd = sessionStorage.getItem('ske_password')
            if (pwd && navigator.serviceWorker.controller) {
                navigator.serviceWorker.controller.postMessage({
                    type: 'SET_PASSWORD',
                    password: pwd
                })
            }
        }
    })
}

createApp(App).use(router).mount('#app')
