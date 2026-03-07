<template>
  <div id="ske-app">
    <!-- persistent Top Bar for Browsing -->
    <header v-if="route.name === 'Browse'" class="topbar glass-card">
      <div class="topbar-left">
        <img src="/logo.png" alt="Logo" width="24" height="24" style="margin-right: 8px; border-radius: 4px;" />
        <div class="brand text-gradient">Sakura Encryptor</div>
        <div class="breadcrumb">
          <span class="crumb clickable" @click="navigateTo('/')">🏠 根目录</span>
          <template v-for="(seg, i) in globalState.decryptedSegments" :key="i">
            <span class="crumb-sep">/</span>
            <span class="crumb clickable" @click="navigateTo(breadcrumbPath(i))">{{ seg }}</span>
          </template>
        </div>
      </div>
      <button class="btn btn-ghost" @click="handleLogout">退出</button>
    </header>

    <router-view v-slot="{ Component, route: r }">
      <transition name="fade" mode="out-in">
        <component :is="Component" :key="r.path" />
      </transition>
    </router-view>
  </div>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import { globalState } from './composables/useGlobalState.js'

const route = useRoute()
const router = useRouter()

function breadcrumbPath(index) {
  const p = route.params.path
  const parts = Array.isArray(p) ? p : (p || '').split('/').filter(Boolean)
  return '/' + parts.slice(0, index + 1).join('/')
}

function navigateTo(path) {
  router.push('/browse' + path)
}

function handleLogout() {
  sessionStorage.clear()
  router.push('/login')
}
</script>

<style>
/* Global Layout Styles */
#ske-app {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

/* ── Top Bar (Moved from BrowserView) ── */
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  margin: 16px 16px 0;
  border-radius: var(--radius-md);
  position: sticky;
  top: 16px;
  z-index: 100;
  flex-shrink: 0;
  gap: 12px;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
  flex: 1;
}

.brand {
  font-size: 18px;
  font-weight: 700;
  flex-shrink: 0;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
  flex: 1;
  overflow-x: auto;
  scrollbar-width: none; /* Firefox */
  white-space: nowrap;
}

.breadcrumb::-webkit-scrollbar {
  display: none; /* Chrome/Safari */
}

.crumb.clickable {
  cursor: pointer;
  transition: color var(--transition-fast);
  flex-shrink: 0;
}

.crumb.clickable:hover {
  color: var(--accent-start);
}

.crumb-sep {
  color: var(--text-muted);
  flex-shrink: 0;
}

/* ── Mobile Responsive Logic ── */
@media (max-width: 600px) {
  .topbar {
    margin: 8px 8px 0;
    padding: 8px 12px;
    top: 8px;
  }
  
  .brand {
    display: none; /* Hide brand on mobile to save space */
  }

  .topbar-left {
    gap: 8px;
  }
}

/* Transitions */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
