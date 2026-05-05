<template>
  <div v-if="hasTasks" ref="managerRef" class="dl-manager" :style="managerStyle">
    <!-- Toggle Button -->
    <button class="dl-fab" @click="expanded = !expanded" :title="`${activeCount} 个下载中`">
      <span class="dl-fab-icon" :class="{ pulse: activeCount > 0 }">⬇️</span>
      <span v-if="activeCount > 0" class="dl-badge">{{ activeCount }}</span>
    </button>

    <!-- Panel -->
    <Transition name="panel">
      <div v-if="expanded" class="dl-panel glass-card" :style="mobilePanelStyle">
        <div class="dl-header">
          <span class="dl-title">下载管理</span>
          <button class="dl-clear" @click="clearCompleted" title="清除已完成">清除</button>
        </div>

        <div class="dl-list">
          <div v-for="task in tasks" :key="task.id" class="dl-item">
            <div class="dl-item-info">
              <span class="dl-item-name truncate">{{ task.name }}</span>
              <span class="dl-item-status">
                <template v-if="task.status === 'downloading'">
                  {{ formatBytes(task.loaded) }}{{ task.total ? ' / ' + formatBytes(task.total) : '' }}
                  <span v-if="task.speed > 0" class="dl-speed">{{ formatSpeed(task.speed) }}</span>
                </template>
                <template v-else-if="task.status === 'paused'">
                  ⏸ 暂停 · {{ formatBytes(task.loaded) }}{{ task.total ? ' / ' + formatBytes(task.total) : '' }}
                </template>
                <template v-else-if="task.status === 'completed'">✅ 完成</template>
                <template v-else-if="task.status === 'cancelled'">⛔ 已取消</template>
                <template v-else-if="task.status === 'failed'">❌ {{ task.error }}</template>
              </span>
            </div>

            <!-- Progress bar -->
            <div v-if="task.status === 'downloading' || task.status === 'paused'" class="dl-progress">
              <div
                class="dl-progress-fill"
                :class="{ indeterminate: !task.total }"
                :style="task.total ? { width: task.progress + '%' } : {}"
              ></div>
            </div>

            <!-- Actions -->
            <div class="dl-actions">
              <button
                v-if="task.status === 'downloading'"
                class="dl-btn"
                @click="pauseTask(task.id)"
                title="暂停"
              >⏸</button>
              <button
                v-if="task.status === 'paused'"
                class="dl-btn"
                @click="resumeTask(task.id)"
                title="继续"
              >▶</button>
              <button
                v-if="task.status === 'downloading' || task.status === 'paused'"
                class="dl-btn"
                @click="cancelTask(task.id)"
                title="取消"
              >✕</button>
              <button
                v-if="task.status === 'failed' || task.status === 'cancelled'"
                class="dl-btn"
                @click="retryTask(task.id)"
                title="重试"
              >↻</button>
              <button
                v-if="task.status !== 'downloading' && task.status !== 'paused'"
                class="dl-btn"
                @click="removeTask(task.id)"
                title="移除"
              >✕</button>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useDownloadManager } from '../../composables/useDownloadManager.js'
import { useFabManager, FAB_BASE_DESKTOP, FAB_BASE_MOBILE, FAB_SIZE, FAB_GAP } from '../../composables/useFabManager.js'

const { tasks, activeCount, hasTasks, cancelTask, pauseTask, resumeTask, retryTask, removeTask, clearCompleted } = useDownloadManager()
const { count } = useFabManager()
const managerRef = ref(null)
const expanded = ref(false)
const managerStyle = computed(() => ({
  '--dl-desktop-bottom': `${FAB_BASE_DESKTOP + count.value * (FAB_SIZE + FAB_GAP)}px`,
  '--dl-mobile-bottom': `${FAB_BASE_MOBILE + count.value * (FAB_SIZE + FAB_GAP)}px`,
}))
const mobilePanelStyle = computed(() => {
  const estimatedHeight = 64 + tasks.length * 82
  return {
    '--dl-mobile-sheet-height': `${Math.max(160, Math.min(estimatedHeight, 480))}px`,
  }
})

function formatBytes(b) {
  if (!b) return '0 B'
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB'
  if (b < 1024 * 1024 * 1024) return (b / (1024 * 1024)).toFixed(1) + ' MB'
  return (b / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

function formatSpeed(bps) {
  if (bps < 1024) return bps.toFixed(0) + ' B/s'
  if (bps < 1024 * 1024) return (bps / 1024).toFixed(1) + ' KB/s'
  return (bps / (1024 * 1024)).toFixed(1) + ' MB/s'
}

function handleDocumentPointerDown(e) {
  if (!expanded.value) return
  if (managerRef.value?.contains(e.target)) return
  expanded.value = false
}

watch(hasTasks, (value) => {
  if (!value) expanded.value = false
})

onMounted(() => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
})
</script>

<style scoped>
.dl-manager {
  --dl-desktop-bottom: 20px;
  --dl-mobile-bottom: 16px;
  position: fixed;
  bottom: var(--dl-desktop-bottom);
  right: 28px;
  z-index: 200;
  display: flex;
  flex-direction: column-reverse;
  align-items: flex-end;
  gap: 10px;
}

/* ── FAB ── */
.dl-fab {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(22, 22, 50, 0.9);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.4);
  position: relative;
  transition: all 0.2s;
}

.dl-fab:hover {
  border-color: rgba(255, 255, 255, 0.25);
  transform: scale(1.08);
}

.dl-fab-icon.pulse {
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.dl-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  background: #8b5cf6;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ── Panel ── */
.dl-panel {
  width: 300px;
  max-height: 360px;
  display: flex;
  flex-direction: column;
  background: rgba(18, 18, 40, 0.95) !important;
  border: 1px solid rgba(255, 255, 255, 0.12) !important;
  border-radius: 14px !important;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.6) !important;
  overflow: hidden;
}

.dl-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px 10px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.dl-title {
  font-size: 14px;
  font-weight: 700;
  color: #e8e8f0;
}

.dl-clear {
  background: none;
  border: none;
  color: #9090b0;
  font-size: 12px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 6px;
  transition: all 0.15s;
}

.dl-clear:hover {
  color: #e8e8f0;
  background: rgba(255, 255, 255, 0.06);
}

.dl-list {
  overflow-y: auto;
  padding: 8px 0;
}

/* ── Item ── */
.dl-item {
  padding: 10px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  position: relative;
}

.dl-item-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.dl-item-name {
  font-size: 13px;
  font-weight: 500;
  color: #e8e8f0;
}

.dl-item-status {
  font-size: 11px;
  color: #9090b0;
}

/* ── Progress ── */
.dl-progress {
  height: 3px;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 2px;
  overflow: hidden;
}

.dl-progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #8b5cf6, #06b6d4);
  border-radius: 2px;
  transition: width 0.3s ease;
}

.dl-progress-fill.indeterminate {
  width: 100% !important;
  background: linear-gradient(90deg, transparent, #8b5cf6, #06b6d4, transparent);
  background-size: 200% 100%;
  animation: indeterminate 1.5s ease-in-out infinite;
}

@keyframes indeterminate {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.dl-speed {
  margin-left: 8px;
  color: #8b5cf6;
}

/* ── Actions ── */
.dl-actions {
  position: absolute;
  top: 10px;
  right: 12px;
  display: flex;
  gap: 4px;
}

.dl-btn {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  border: none;
  background: rgba(255, 255, 255, 0.06);
  color: #9090b0;
  font-size: 12px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}

.dl-btn:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #e8e8f0;
}

/* ── Panel Transition ── */
.panel-enter-active,
.panel-leave-active {
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}

.panel-enter-from,
.panel-leave-to {
  opacity: 0;
  transform: translateY(10px) scale(0.95);
}

@media (max-width: 600px) {
  .dl-manager {
    bottom: var(--dl-mobile-bottom);
    right: 16px;
  }

  .dl-fab {
    width: 40px;
    height: 40px;
    font-size: 16px;
  }

  .dl-panel {
    position: fixed;
    left: 12px;
    right: 12px;
    bottom: calc(var(--dl-mobile-bottom) + 52px);
    width: auto;
    max-height: min(var(--dl-mobile-sheet-height), 55vh);
    border-radius: 18px 18px 12px 12px !important;
  }

  .dl-header {
    padding: 16px 18px 12px;
  }

  .dl-title {
    font-size: 15px;
  }

  .dl-clear {
    font-size: 13px;
    padding: 4px 8px;
  }

  .dl-list {
    padding: 8px 0 max(env(safe-area-inset-bottom), 8px);
  }

  .dl-item {
    padding: 12px 18px;
    gap: 8px;
  }

  .dl-item-name {
    font-size: 14px;
    padding-right: 88px;
  }

  .dl-item-status {
    font-size: 12px;
  }

  .dl-progress {
    height: 4px;
  }

  .dl-actions {
    top: 12px;
    right: 16px;
    gap: 6px;
  }

  .dl-btn {
    width: 28px;
    height: 28px;
    font-size: 14px;
  }

  .panel-enter-from,
  .panel-leave-to {
    opacity: 0;
    transform: translateY(18px);
  }
}
</style>
