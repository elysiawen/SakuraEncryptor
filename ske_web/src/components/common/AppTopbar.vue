<template>
  <header class="topbar glass-card">
    <div class="topbar-left">
      <img src="/logo.png" alt="Logo" width="24" height="24" style="border-radius: 4px;" />
      <div class="brand text-gradient">Sakura Encryptor</div>
      <div v-if="showBreadcrumbs" class="breadcrumb">
        <span class="crumb clickable" @click="navigateTo('/')">🏠 根目录</span>
        <template v-for="(seg, i) in globalState.decryptedSegments" :key="i">
          <span class="crumb-sep">/</span>
          <span class="crumb clickable" @click="navigateTo(breadcrumbPath(i))">{{ seg }}</span>
        </template>
      </div>
    </div>
    <n-button quaternary size="small" @click="handleLogout">退出</n-button>
  </header>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import { globalState } from '../../composables/useGlobalState.js'
import { useLocalFiles } from '../../composables/useLocalFiles.js'

defineProps({
  showBreadcrumbs: { type: Boolean, default: false },
})

const route = useRoute()
const router = useRouter()
const { clearFiles } = useLocalFiles()

function breadcrumbPath(index) {
  const p = route.params.path
  const parts = Array.isArray(p) ? p : (p || '').split('/').filter(Boolean)
  return '/' + parts.slice(0, index + 1).join('/')
}

function navigateTo(path) {
  router.push('/browse' + path)
}

function handleLogout() {
  clearFiles()
  sessionStorage.clear()
  router.push('/login')
}
</script>

<style scoped>
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  margin: 0;
  border-radius: 0;
  position: sticky;
  top: 0;
  z-index: 100;
  flex-shrink: 0;
  gap: 12px;
  border-bottom: 1px solid var(--border-glass);
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
  scrollbar-width: none;
  white-space: nowrap;
}

.breadcrumb::-webkit-scrollbar {
  display: none;
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

@media (max-width: 600px) {
  .topbar {
    padding: 10px 12px;
  }

  .brand {
    display: none;
  }

  .topbar-left {
    gap: 8px;
  }
}
</style>
