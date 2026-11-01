import { createRouter, createWebHashHistory } from 'vue-router'

// Hash history: the app is served from a loopback origin in production and from
// Vite in development. Hash routing works identically in both without needing
// the server to fall back for deep links.
//
// `meta.title` holds a translation key, not a sentence: the sidebar and any
// future window-title code look it up at render time, so a language switch does
// not leave stale strings in the route table.
const routes = [
  { path: '/', redirect: '/browse' },
  {
    path: '/encrypt',
    name: 'encrypt',
    component: () => import('./views/EncryptView.vue'),
    meta: { title: 'nav.encrypt' },
  },
  {
    path: '/browse/:path(.*)*',
    name: 'browse',
    component: () => import('./views/BrowseView.vue'),
    meta: { title: 'nav.browse' },
  },
  {
    path: '/local/:path(.*)*',
    name: 'local',
    component: () => import('./views/LocalView.vue'),
    meta: { title: 'nav.local' },
  },
  {
    path: '/transfers',
    name: 'transfers',
    component: () => import('./views/TransfersView.vue'),
    meta: { title: 'nav.transfers' },
  },
  // Old links and a restored session may still point at the download page.
  { path: '/downloads', redirect: '/transfers' },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('./views/SettingsView.vue'),
    meta: { title: 'nav.settings' },
  },
  {
    path: '/profiles',
    name: 'profiles',
    component: () => import('./views/ProfilesView.vue'),
    meta: { title: 'nav.profiles' },
  },
  {
    path: '/diagnostics',
    name: 'diagnostics',
    component: () => import('./views/DiagnosticsView.vue'),
    meta: { title: 'nav.diagnostics' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/browse' },
]

export default createRouter({
  history: createWebHashHistory(),
  routes,
})
