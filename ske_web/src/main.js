import { createApp } from 'vue'
import naive, { darkTheme } from 'naive-ui'
import App from './App.vue'
import router from './router.js'
import './theme/global.css'
import './theme/naive-overrides.css'

// ── Naive UI Theme Overrides ──────────────────────────────────
export const themeOverrides = {
  common: {
    primaryColor: '#8b5cf6',
    primaryColorHover: '#a78bfa',
    primaryColorPressed: '#7c3aed',
    primaryColorSuppl: '#8b5cf6',
    infoColor: '#06b6d4',
    successColor: '#22c55e',
    warningColor: '#f59e0b',
    errorColor: '#ef4444',
    bodyColor: '#0a0a14',
    cardColor: 'rgba(22, 22, 50, 0.65)',
    modalColor: 'rgba(22, 22, 50, 0.85)',
    popoverColor: 'rgba(22, 22, 50, 0.85)',
    tableColor: 'rgba(22, 22, 50, 0.65)',
    inputColor: 'rgba(255, 255, 255, 0.04)',
    hoverColor: 'rgba(255, 255, 255, 0.08)',
    borderColor: 'rgba(255, 255, 255, 0.08)',
    dividerColor: 'rgba(255, 255, 255, 0.08)',
    textColorBase: '#e8e8f0',
    textColor1: '#e8e8f0',
    textColor2: '#9090b0',
    textColor3: '#606080',
    borderRadius: '12px',
    borderRadiusSmall: '8px',
    fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
    fontSize: '14px',
  },
  Button: {
    borderRadiusMedium: '12px',
    fontWeightStrong: '600',
  },
  Input: {
    borderRadius: '12px',
    color: 'rgba(255, 255, 255, 0.04)',
    colorFocus: 'rgba(255, 255, 255, 0.04)',
    border: '1px solid rgba(255, 255, 255, 0.08)',
    borderHover: '1px solid rgba(255, 255, 255, 0.15)',
    borderFocus: '1px solid #8b5cf6',
    boxShadowFocus: '0 0 0 3px rgba(139, 92, 246, 0.15)',
  },
  Card: {
    borderRadius: '18px',
    color: 'rgba(22, 22, 50, 0.65)',
    borderColor: 'rgba(255, 255, 255, 0.08)',
  },
  Tabs: {
    tabBorderRadius: '12px',
    tabColorSegment: 'rgba(255, 255, 255, 0.05)',
    tabColorSegmentActive: 'rgba(139, 92, 246, 0.2)',
    tabTextColorSegment: '#9090b0',
    tabTextColorSegmentActive: '#e8e8f0',
    tabTextColorHoverSegment: '#e8e8f0',
  },
  Tag: {
    borderRadius: '6px',
  },
  Breadcrumb: {
    separatorColor: '#606080',
    itemTextColor: '#9090b0',
    itemTextColorHover: '#8b5cf6',
  },
}

// ── Service Worker Registration ───────────────────────────────
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
          password: pwd,
        })
      }
    }
  })
}

// ── Mount App ─────────────────────────────────────────────────
createApp(App).use(naive).use(router).mount('#app')
