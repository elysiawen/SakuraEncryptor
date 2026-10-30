<template>
  <aside class="sidebar">
    <!-- The wordmark moved up into the window caption (TitleBar): one brand per
         window, and the rail starts straight with navigation. -->
    <nav class="nav">
      <RouterLink
        v-for="item in items"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :class="{ active: isActive(item) }"
      >
        <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
             stroke-linecap="round" stroke-linejoin="round">
          <template v-for="(shape, i) in item.shapes" :key="i">
            <path v-if="shape.d" :d="shape.d" />
            <circle v-else :cx="shape.cx" :cy="shape.cy" :r="shape.r" />
          </template>
        </svg>
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

    <div class="spacer" />

    <!-- Audio keeps playing in the shell, so its transport lives here rather
         than in the page that started it. -->
    <MusicBar />

    <div class="utility">
      <button class="ghost utility-btn" type="button" :title="themeLabel" @click="onTheme">
        <svg v-if="theme === 'light'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
          <path d="M20 14.6A8.1 8.1 0 0 1 9.4 4a8.2 8.2 0 1 0 10.6 10.6z" />
        </svg>
        <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
          <circle cx="12" cy="12" r="4.1" />
          <path d="M12 3v2.2M12 18.8V21M3 12h2.2M18.8 12H21M5.7 5.7l1.5 1.5M16.8 16.8l1.5 1.5M18.3 5.7l-1.5 1.5M7.2 16.8l-1.5 1.5" />
        </svg>
        <span>{{ theme === 'light' ? '浅色' : '深色' }}</span>
      </button>

      <button
        v-if="session.lock.enabled"
        class="ghost utility-btn"
        type="button"
        title="锁定应用"
        @click="onLock"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
          <path d="M7 11V8a5 5 0 0 1 10 0v3" />
          <path d="M5.5 11h13V19a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19v-8z" />
        </svg>
        <span>锁定</span>
      </button>
    </div>

    <RouterLink to="/profiles" class="profile" :class="{ empty: !activeProfile }">
      <div class="avatar">{{ initials }}</div>
      <div class="info">
        <div class="name">{{ activeProfile?.name || '未选择配置' }}</div>
        <div class="server">{{ activeProfile?.server || '添加云端账号' }}</div>
      </div>
      <svg class="chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
        <path d="M9 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </RouterLink>
  </aside>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import MusicBar from './MusicBar.vue'
import { activeProfile, attempt, lock, session } from '../state/session.js'
import { currentTheme, toggleTheme } from '../state/theme.js'

const route = useRoute()

const theme = ref(currentTheme())
const themeLabel = computed(() => (theme.value === 'light' ? '切换到深色主题' : '切换到浅色主题'))

async function onTheme() {
  theme.value = await toggleTheme()
}

function onLock() {
  attempt(lock)
}

const items = [
  {
    to: '/encrypt',
    label: '加密工作台',
    shapes: [{ d: 'M7 11V8a5 5 0 0 1 10 0v3' }, { d: 'M5.5 11h13V19a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19v-8z' }],
  },
  {
    to: '/browse',
    label: '云端库',
    shapes: [{ d: 'M7 18h10a4 4 0 0 0 .4-8A6 6 0 0 0 6 11a3.5 3.5 0 0 0 1 7z' }],
  },
  {
    to: '/local',
    label: '本地库',
    shapes: [{ d: 'M4 8a2 2 0 0 1 2-2h3.5l2 2H18a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8z' }],
  },
  {
    to: '/transfers',
    label: '传输',
    // Two arrows, one per direction: the page carries uploads as well.
    shapes: [
      { d: 'M12 4v16' },
      { d: 'M8 8l4-4 4 4' },
      { d: 'M8 16l4 4 4-4' },
    ],
  },
  {
    to: '/settings',
    label: '设置',
    shapes: [
      { d: 'M4 8h16' },
      { d: 'M4 16h16' },
      { cx: 9, cy: 8, r: 2.2 },
      { cx: 15, cy: 16, r: 2.2 },
    ],
  },
]

function isActive(item) {
  return route.path === item.to || route.path.startsWith(`${item.to}/`)
}

const initials = computed(() => {
  const name = activeProfile.value?.name?.trim()
  return name ? name.slice(0, 1).toUpperCase() : '云'
})
</script>

<style scoped>
.sidebar {
  width: 210px;
  flex: 0 0 210px;
  display: flex;
  flex-direction: column;
  background: var(--bg-elevated);
  border-right: 1px solid var(--line);
  padding: 12px 10px 10px;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  color: var(--text-dim);
  font-size: 13px;
  transition: background 0.14s ease, color 0.14s ease;
}

/* The active marker grows out of the row instead of appearing whole: the eye
   follows it when navigating rather than re-reading the list. */
.nav-item::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 50%;
  width: 3px;
  height: 15px;
  margin-top: -7.5px;
  border-radius: 999px;
  background: var(--accent-strong);
  opacity: 0;
  transform: scaleY(0.25);
  transition: opacity 0.18s ease, transform 0.22s cubic-bezier(0.22, 0.61, 0.36, 1);
}

.nav-item:hover {
  background: var(--panel);
  color: var(--text);
}

.nav-item.active {
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.nav-item.active::before {
  opacity: 1;
  transform: scaleY(1);
}

.icon {
  width: 17px;
  height: 17px;
  flex: 0 0 17px;
  transition: transform 0.18s ease;
}

.nav-item:hover .icon {
  transform: translateX(1px);
}

.spacer {
  flex: 1;
}

.utility {
  display: flex;
  gap: 6px;
  padding: 0 2px 8px;
}

.utility-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 6px 0;
  font-size: 11.5px;
  color: var(--text-dim);
}

.utility-btn:hover:not(:disabled) {
  color: var(--text);
}

.utility-btn svg {
  width: 15px;
  height: 15px;
  flex: 0 0 15px;
}

.profile {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 10px;
  border-radius: var(--radius);
  border: 1px solid var(--line);
  background: var(--panel);
  transition: border-color 0.14s ease;
}

.profile:hover {
  border-color: var(--accent);
}

.profile.empty .avatar {
  background: var(--line);
  color: var(--muted);
}

.profile.empty .name,
.profile.empty .server {
  color: var(--muted);
}

.avatar {
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  border-radius: 8px;
  background: var(--accent-soft);
  color: var(--accent-strong);
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 600;
}

.info {
  min-width: 0;
  flex: 1;
}

.name {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.server {
  font-size: 10.5px;
  color: var(--muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chevron {
  width: 14px;
  height: 14px;
  color: var(--muted);
}
</style>
