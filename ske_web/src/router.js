import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/login' },
  {
    path: '/login',
    name: 'Login',
    component: () => import('./views/LoginView.vue'),
  },
  {
    path: '/browse/:path(.*)*',
    name: 'Browse',
    component: () => import('./views/BrowseView.vue'),
  },
  {
    path: '/local',
    name: 'Local',
    component: () => import('./views/LocalView.vue'),
  },
  {
    path: '/play/:path(.*)*',
    name: 'Play',
    component: () => import('./views/PlayerView.vue'),
  },
  {
    path: '/view/:path(.*)*',
    name: 'View',
    component: () => import('./views/ImageView.vue'),
  },
  {
    path: '/listen/:path(.*)*',
    name: 'Listen',
    component: () => import('./views/MusicView.vue'),
  },
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
