<template>
  <Teleport to="body">
    <Transition name="overlay">
      <div v-if="visible" class="playlist-overlay" @click="$emit('close')" />
    </Transition>
    <Transition name="panel">
      <div v-if="visible" class="playlist-panel glass-card">
        <div class="playlist-header">
          <span class="playlist-title">播放列表</span>
          <span class="playlist-count">{{ items.length }} {{ label }}</span>
          <button class="close-btn" @click="$emit('close')">✕</button>
        </div>
        <div class="playlist-list">
          <div
            v-for="(item, i) in items"
            :key="item.encName"
            class="playlist-item"
            :class="{ active: i === currentIndex }"
            @click="$emit('select', item)"
          >
            <span class="item-index">{{ i + 1 }}</span>
            <span class="item-name truncate">{{ item.decName }}</span>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
defineProps({
  items: { type: Array, default: () => [] },
  currentIndex: { type: Number, default: -1 },
  visible: { type: Boolean, default: false },
  label: { type: String, default: '首' },
})

defineEmits(['close', 'select'])
</script>

<style scoped>
.playlist-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 9998;
}

.playlist-panel {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: 320px;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  background: rgba(16, 14, 36, 0.96);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border-left: 1px solid var(--border-glass);
  border-radius: 0;
  padding: 0;
}

.playlist-header {
  display: flex;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-glass);
  gap: 8px;
  flex-shrink: 0;
}

.playlist-title {
  font-size: 15px;
  font-weight: 700;
  color: #fff;
}

.playlist-count {
  font-size: 12px;
  color: var(--text-muted);
  margin-left: auto;
}

.close-btn {
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: 16px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: all 0.15s;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.playlist-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.playlist-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 20px;
  cursor: pointer;
  transition: background 0.15s;
}

.playlist-item:hover {
  background: rgba(255, 255, 255, 0.06);
}

.playlist-item.active {
  background: rgba(139, 92, 246, 0.15);
}

.playlist-item.active .item-name {
  color: #a78bfa;
  font-weight: 600;
}

.playlist-item.active .item-index {
  color: #a78bfa;
}

.item-index {
  font-size: 12px;
  color: var(--text-muted);
  min-width: 20px;
  text-align: center;
}

.item-name {
  font-size: 13px;
  color: var(--text-primary);
  min-width: 0;
}

/* Transitions */
.overlay-enter-active,
.overlay-leave-active {
  transition: opacity 0.25s ease;
}
.overlay-enter-from,
.overlay-leave-to {
  opacity: 0;
}

.panel-enter-active,
.panel-leave-active {
  transition: transform 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}
.panel-enter-from,
.panel-leave-to {
  transform: translateX(100%);
}

@media (max-width: 600px) {
  .playlist-panel {
    top: auto;
    left: 0;
    right: 0;
    bottom: 0;
    width: 100%;
    max-height: 60vh;
    border-left: none;
    border-top: 1px solid var(--border-glass);
    border-radius: 16px 16px 0 0;
  }

  .panel-enter-from,
  .panel-leave-to {
    transform: translateY(100%);
  }
}
</style>
