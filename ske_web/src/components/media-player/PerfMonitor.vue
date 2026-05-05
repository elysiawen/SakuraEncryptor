<template>
  <div class="perf-bar glass-card">
    <div class="perf-item" title="视频帧率">
      <span class="perf-icon">🎞️</span>
      <span class="perf-value">{{ perf.fps }}</span>
      <span class="perf-unit">FPS</span>
    </div>
    <div class="perf-item" :class="{ 'perf-warn': perf.droppedPct > 5 }" title="丢帧率">
      <span class="perf-icon">📉</span>
      <span class="perf-value">{{ perf.dropped }}</span>
      <span class="perf-unit">丢帧</span>
    </div>
    <div class="perf-item" title="JS 堆内存使用">
      <span class="perf-icon">🧠</span>
      <span class="perf-value">{{ perf.heapUsed }}</span>
      <span class="perf-unit">/ {{ perf.heapTotal }}</span>
    </div>
    <div class="perf-item" title="已传输数据量">
      <span class="perf-icon">📡</span>
      <span class="perf-value">{{ perf.netTransferred }}</span>
      <span class="perf-unit">传输</span>
    </div>
    <div class="perf-item" title="缓存命中率">
      <span class="perf-icon">💾</span>
      <span class="perf-value">{{ perf.cacheHitRate }}</span>
      <span class="perf-unit">缓存</span>
    </div>
    <div class="perf-item" title="实时解密延迟 (每块)">
      <span class="perf-icon">⚡</span>
      <span class="perf-value">{{ perf.decryptLatency }}</span>
      <span class="perf-unit">ms</span>
    </div>
    <div class="perf-item" title="实时下载网速">
      <span class="perf-icon">🌐</span>
      <span class="perf-value">{{ perf.netSpeed }}</span>
      <span class="perf-unit">/s</span>
    </div>
  </div>
</template>

<script setup>
defineProps({
  perf: { type: Object, required: true },
})
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
