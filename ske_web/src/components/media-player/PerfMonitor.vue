<template>
  <div class="perf-bar glass-card">
    <div class="perf-item" :title="t('perf.fps')">
      <span class="perf-icon">🎞️</span>
      <span class="perf-value">{{ perf.fps }}</span>
      <span class="perf-unit">FPS</span>
    </div>
    <div class="perf-item" :class="{ 'perf-warn': perf.droppedPct > 5 }" :title="t('perf.droppedRate')">
      <span class="perf-icon">📉</span>
      <span class="perf-value">{{ perf.dropped }}</span>
      <span class="perf-unit">{{ t('perf.dropped') }}</span>
    </div>
    <div class="perf-item" :title="t('perf.heap')">
      <span class="perf-icon">🧠</span>
      <span class="perf-value">{{ perf.heapUsed }}</span>
      <span class="perf-unit">/ {{ perf.heapTotal }}</span>
    </div>
    <div class="perf-item" :title="t('perf.transferred')">
      <span class="perf-icon">📡</span>
      <span class="perf-value">{{ perf.netTransferred }}</span>
      <span class="perf-unit">{{ t('perf.transfer') }}</span>
    </div>
    <div class="perf-item" :title="t('perf.cacheRate')">
      <span class="perf-icon">💾</span>
      <span class="perf-value">{{ perf.cacheHitRate }}</span>
      <span class="perf-unit">{{ t('perf.cache') }}</span>
    </div>
    <div class="perf-item" :title="t('perf.decryptLatency')">
      <span class="perf-icon">⚡</span>
      <span class="perf-value">{{ perf.decryptLatency }}</span>
      <span class="perf-unit">ms</span>
    </div>
    <div class="perf-item" :title="t('perf.speed')">
      <span class="perf-icon">🌐</span>
      <span class="perf-value">{{ perf.netSpeed }}</span>
      <span class="perf-unit">/s</span>
    </div>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'

defineProps({
  perf: { type: Object, required: true },
})

const { t } = useI18n()
</script>

<style scoped>
.perf-bar {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 14px 24px;
  border-radius: var(--radius-md);
  flex-wrap: wrap;
}

.perf-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  transition: color 0.2s;
}

.perf-icon {
  font-size: 11px;
}

.perf-value {
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  font-family: 'SF Mono', 'Cascadia Code', 'Fira Code', monospace;
}

.perf-unit {
  font-size: 10px;
  opacity: 0.7;
}

.perf-warn .perf-value {
  color: #f59e0b;
}

@media (max-width: 600px) {
  .perf-bar {
    gap: 12px;
    padding: 12px 16px;
  }
}
</style>
