<template>
  <header class="topbar glass-card">
    <div class="topbar-left">
      <img src="/logo.png" alt="Logo" width="24" height="24" style="border-radius: 4px;" />
      <div class="brand text-gradient">Sakura Encryptor</div>
      <div v-if="showBreadcrumbs" class="breadcrumb">
        <span class="crumb clickable" @click="navigateTo('/')">🏠 {{ t('topbar.root') }}</span>
        <template v-for="(seg, i) in globalState.decryptedSegments" :key="i">
          <span class="crumb-sep">/</span>
          <span class="crumb clickable" @click="navigateTo(breadcrumbPath(i))">{{ seg }}</span>
        </template>
      </div>
    </div>
    <div class="topbar-actions">
      <LanguageSwitcher icon-only />
      <n-button quaternary size="small" :title="t('topbar.signOut')" @click="handleLogout">
        <svg
          class="logout-icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.7"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3" />
          <path d="M10 8 6 12l4 4" />
          <path d="M6 12h9" />
        </svg>
      </n-button>
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { globalState } from '../../composables/useGlobalState.js'
import { useLocalFiles } from '../../composables/useLocalFiles.js'
import LanguageSwitcher from './LanguageSwitcher.vue'

defineProps({
  showBreadcrumbs: { type: Boolean, default: false },
})

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const { clearFiles } = useLocalFiles()

// Both /browse/:path and /local/:path expose the folder segments in the same
// shape, so one implementation serves both — only the route prefix differs.
const isLocalMode = computed(() => route.name === 'Local')

function breadcrumbPath(index) {
  const p = route.params.path
  const parts = Array.isArray(p) ? p : (p || '').split('/').filter(Boolean)
  return '/' + parts.slice(0, index + 1).join('/')
}

function navigateTo(path) {
  router.push((isLocalMode.value ? '/local' : '/browse') + path)
}

function handleLogout() {
  clearFiles()
  sessionStorage.clear()
  router.push('/login')
}
</script>

<style scoped>
.logout-icon {
  width: 16px;
  height: 16px;
  display: block;
  /* Door on the left, arrow leaving to the right: signing out, not in. */
  transform: scaleX(-1);
}

/* The two icon buttons read as one cluster, not as two separate actions. */
.topbar-actions {
  display: flex;
  align-items: center;
  gap: 0;
  flex-shrink: 0;
}

.topbar-actions :deep(.n-button) {
  padding-left: 8px;
  padding-right: 8px;
}

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
    gap: 4px;
  }

  .brand {
    display: none;
  }

  .topbar-left {
    gap: 8px;
  }

  /* Icons are cheap: the touch target stays 32px tall, only the sides shrink. */
  .topbar-actions :deep(.n-button) {
    padding-left: 4px;
    padding-right: 4px;
  }
}
</style>
