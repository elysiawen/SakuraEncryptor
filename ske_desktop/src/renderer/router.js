import { createRouter, createWebHashHistory } from 'vue-router'

// Hash history: the app is served from a loopback origin in production and from
// Vite in development. Hash routing works identically in both without needing
// the server to fall back for deep links.
const routes = [
  { path: '/', redirect: '/browse' },
  {
    path: '/encrypt',
    name: 'encrypt',
    component: () => import('./views/EncryptView.vue'),
    meta: { title: '加密工作台' },
  },
  {
    path: '/browse/:path(.*)*',
    name: 'browse',
    component: () => import('./views/BrowseView.vue'),
    meta: { title: '云端库' },
  },
  {
    path: '/local/:path(.*)*',
    name: 'local',
    component: () => import('./views/LocalView.vue'),
    meta: { title: '本地库' },
  },
  {
    path: '/transfers',
    name: 'transfers',
    component: () => import('./views/TransfersView.vue'),
    meta: { title: '传输' },
  },
  // Old links and a restored session may still point at the download page.
  { path: '/downloads', redirect: '/transfers' },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('./views/SettingsView.vue'),
    meta: { title: '设置' },
  },
  {
    path: '/profiles',
    name: 'profiles',
    component: () => import('./views/ProfilesView.vue'),
    meta: { title: '云端配置' },
  },
  {
    path: '/diagnostics',
    name: 'diagnostics',
    component: () => import('./views/DiagnosticsView.vue'),
    meta: { title: '播放诊断' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/browse' },
]

export default createRouter({
  history: createWebHashHistory(),
  routes,
})
