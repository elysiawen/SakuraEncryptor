import { createRouter, createWebHistory } from 'vue-router'

import LoginView from './views/LoginView.vue'
import BrowserView from './views/BrowserView.vue'
import PlayerView from './views/PlayerView.vue'
import ImageView from './views/ImageView.vue'
import MusicView from './views/MusicView.vue'

const routes = [
    { path: '/', redirect: '/login' },
    { path: '/login', name: 'Login', component: LoginView },
    { path: '/browse/:path(.*)*', name: 'Browse', component: BrowserView },
    { path: '/play/:path(.*)*', name: 'Play', component: PlayerView },
    { path: '/view/:path(.*)*', name: 'View', component: ImageView },
    { path: '/listen/:path(.*)*', name: 'Listen', component: MusicView },
]

const router = createRouter({
    history: createWebHistory(),
    routes,
})

// Guard: require session key before browsing
router.beforeEach((to) => {
    if (to.name !== 'Login') {
        const hasKey = sessionStorage.getItem('ske_key_exported')
        if (!hasKey) return { name: 'Login' }
    }
})

export default router
