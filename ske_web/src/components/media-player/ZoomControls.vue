<template>
  <div class="zoom-controls glass-card" :class="{ 'ui-hidden': !visible }" @click.stop>
    <button @click="$emit('zoom-out')">➖</button>
    <span class="zoom-text">{{ Math.round(zoom * 100) }}%</span>
    <button @click="$emit('zoom-in')">➕</button>
    <button @click="$emit('reset')">🏠</button>
  </div>
</template>

<script setup>
defineProps({
  zoom: { type: Number, default: 1 },
  visible: { type: Boolean, default: true },
})

defineEmits(['zoom-in', 'zoom-out', 'reset'])
</script>

<style scoped>
.zoom-controls {
  position: absolute;
  bottom: 30px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 15px;
  padding: 10px 20px;
  border-radius: 50px;
  z-index: 10;
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.zoom-controls.ui-hidden {
  transform: translate(-50%, 100px);
  opacity: 0;
  pointer-events: none;
}

.zoom-controls button {
  background: transparent;
  border: none;
  color: white;
  font-size: 18px;
  cursor: pointer;
  padding: 8px;
  border-radius: 50%;
  transition: background 0.2s;
}

.zoom-controls button:hover {
  background: rgba(255, 255, 255, 0.1);
}

.zoom-text {
  font-family: 'Cascadia Code', monospace;
  font-size: 14px;
  min-width: 50px;
  text-align: center;
}

@media (max-width: 600px) {
  .zoom-controls {
    bottom: 20px;
    gap: 10px;
    padding: 8px 16px;
  }

  .zoom-text {
    font-size: 12px;
  }
}
</style>
