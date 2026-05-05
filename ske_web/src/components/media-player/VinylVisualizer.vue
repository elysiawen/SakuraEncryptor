<template>
  <div class="vinyl-container">
    <div class="vinyl-record" :class="{ spin: isPlaying }">
      <img v-if="coverUrl" :src="coverUrl" class="vinyl-cover" />
      <div v-else class="vinyl-grooves"></div>
      <div class="vinyl-label">
        <span v-if="!coverUrl" class="music-icon">🎵</span>
        <img v-else :src="coverUrl" class="label-cover" />
      </div>
    </div>
    <div class="tone-arm" :class="{ 'arm-active': isPlaying }"></div>
    <div v-if="isPlaying" class="pulse-ring ring-1"></div>
    <div v-if="isPlaying" class="pulse-ring ring-2"></div>
  </div>
</template>

<script setup>
defineProps({
  isPlaying: { type: Boolean, default: false },
  coverUrl: { type: String, default: '' },
})
</script>

<style scoped>
.vinyl-container {
  position: relative;
  width: 280px;
  height: 280px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.vinyl-record {
  position: relative;
  width: 240px;
  height: 240px;
  border-radius: 50%;
  background: #111;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.8), inset 0 0 10px rgba(255, 255, 255, 0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 3;
  transition: transform 0.5s ease;
}

.vinyl-grooves {
  position: absolute;
  top: 4px; left: 4px; right: 4px; bottom: 4px;
  border-radius: 50%;
  background: repeating-radial-gradient(#111, #111 2px, #1a1a1a 3px, #111 4px);
  mask-image: radial-gradient(white, black);
  -webkit-mask-image: radial-gradient(white, black);
  opacity: 0.8;
}

.vinyl-cover {
  position: absolute;
  top: 0; left: 0; width: 100%; height: 100%;
  border-radius: 50%;
  object-fit: cover;
  opacity: 0.6;
  mask-image: radial-gradient(white, black);
  -webkit-mask-image: radial-gradient(white, black);
}

.vinyl-label {
  position: absolute;
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: linear-gradient(135deg, #8b5cf6, #ec4899);
  display: flex;
  align-items: center;
  justify-content: center;
  border: 4px solid #000;
  box-shadow: 0 0 15px rgba(0, 0, 0, 0.5);
  z-index: 2;
  overflow: hidden;
}

.vinyl-label::after {
  content: '';
  position: absolute;
  width: 12px;
  height: 12px;
  background: #000;
  border-radius: 50%;
  box-shadow: inset 0 2px 4px rgba(255, 255, 255, 0.4);
}

.label-cover {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
}

.music-icon {
  font-size: 28px;
  filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.3));
}

.tone-arm {
  position: absolute;
  top: 0;
  right: 20px;
  width: 12px;
  height: 140px;
  background: linear-gradient(90deg, #d4d4d8, #a1a1aa, #d4d4d8);
  border-radius: 6px;
  transform-origin: top center;
  transform: rotate(-35deg);
  transition: transform 0.6s cubic-bezier(0.68, -0.55, 0.27, 1.55);
  box-shadow: 2px 5px 10px rgba(0, 0, 0, 0.5);
  z-index: 4;
}

.tone-arm::before {
  content: '';
  position: absolute;
  top: 4px; left: -6px;
  width: 24px; height: 24px;
  background: #3f3f46;
  border-radius: 50%;
  box-shadow: inset 0 2px 4px rgba(255, 255, 255, 0.3);
}

.tone-arm::after {
  content: '';
  position: absolute;
  bottom: -15px; left: -8px;
  width: 28px; height: 35px;
  background: #27272a;
  border-radius: 4px;
  transform: rotate(-15deg);
}

.vinyl-record.spin {
  animation: spin 10s linear infinite;
}

.tone-arm.arm-active {
  transform: rotate(15deg);
}

@keyframes spin {
  100% { transform: rotate(360deg); }
}

.pulse-ring {
  position: absolute;
  width: 240px;
  height: 240px;
  border-radius: 50%;
  background: rgba(139, 92, 246, 0.3);
  z-index: 1;
  animation: pulse 2s ease-out infinite;
}

.ring-2 {
  animation-delay: 1s;
  background: rgba(236, 72, 153, 0.2);
}

@keyframes pulse {
  0% { transform: scale(1); opacity: 0.8; }
  100% { transform: scale(1.5); opacity: 0; }
}

@media (max-width: 600px) {
  .vinyl-container {
    width: 220px;
    height: 220px;
  }

  .vinyl-record {
    width: 200px;
    height: 200px;
  }

  .vinyl-label {
    width: 60px;
    height: 60px;
  }

  .pulse-ring {
    width: 200px;
    height: 200px;
  }
}
</style>
