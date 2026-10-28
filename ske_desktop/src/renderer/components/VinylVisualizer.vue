<!--
  Vinyl disc for the music page, following the web client: grooves when there is
  no artwork, the cover under the grooves when there is, and a tone arm that
  swings onto the record while it plays.
-->
<template>
  <div class="vinyl" :class="{ playing }">
    <div class="record">
      <img v-if="coverUrl" :src="coverUrl" class="cover" alt="" />
      <div v-else class="grooves" />
      <div class="label">
        <img v-if="coverUrl" :src="coverUrl" class="label-cover" alt="" />
        <span v-else class="note" aria-hidden="true">♪</span>
      </div>
    </div>

    <div class="arm" />

    <template v-if="playing">
      <span class="ring ring-1" />
      <span class="ring ring-2" />
    </template>
  </div>
</template>

<script setup>
defineProps({
  playing: { type: Boolean, default: false },
  coverUrl: { type: String, default: '' },
})
</script>

<style scoped>
.vinyl {
  position: relative;
  width: 264px;
  height: 264px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
}

.record {
  position: relative;
  width: 224px;
  height: 224px;
  border-radius: 50%;
  background: #111;
  box-shadow:
    0 20px 50px rgba(0, 0, 0, 0.8),
    inset 0 0 10px rgba(255, 255, 255, 0.1);
  display: grid;
  place-items: center;
  z-index: 3;
}

.playing .record {
  animation: spin 12s linear infinite;
}

.grooves {
  position: absolute;
  inset: 4px;
  border-radius: 50%;
  background: repeating-radial-gradient(#111, #111 2px, #1a1a1a 3px, #111 4px);
  opacity: 0.85;
  mask-image: radial-gradient(white, black);
  -webkit-mask-image: radial-gradient(white, black);
}

.cover {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
  opacity: 0.62;
  mask-image: radial-gradient(white, black);
  -webkit-mask-image: radial-gradient(white, black);
}

.label {
  position: relative;
  z-index: 2;
  width: 72px;
  height: 72px;
  border-radius: 50%;
  border: 4px solid #000;
  background: linear-gradient(135deg, #8b5cf6, #ec4899);
  display: grid;
  place-items: center;
  overflow: hidden;
  box-shadow: 0 0 15px rgba(0, 0, 0, 0.5);
}

/* Spindle hole, painted over whatever the label holds. */
.label::after {
  content: '';
  position: absolute;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: #000;
  box-shadow: inset 0 2px 4px rgba(255, 255, 255, 0.4);
}

.label-cover {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
}

.note {
  font-size: 26px;
  color: rgba(255, 255, 255, 0.9);
  filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.3));
}

/* Tone arm: pivot at the top, needle swinging towards the grooves. */
.arm {
  position: absolute;
  top: 2px;
  right: 26px;
  width: 11px;
  height: 128px;
  border-radius: 6px;
  background: linear-gradient(90deg, #d4d4d8, #a1a1aa, #d4d4d8);
  transform-origin: top center;
  transform: rotate(-35deg);
  transition: transform 0.6s cubic-bezier(0.68, -0.55, 0.27, 1.55);
  box-shadow: 2px 5px 10px rgba(0, 0, 0, 0.5);
  z-index: 4;
}

.arm::before {
  content: '';
  position: absolute;
  top: 4px;
  left: -7px;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #3f3f46;
  box-shadow: inset 0 2px 4px rgba(255, 255, 255, 0.3);
}

.arm::after {
  content: '';
  position: absolute;
  bottom: -14px;
  left: -8px;
  width: 27px;
  height: 33px;
  border-radius: 4px;
  background: #27272a;
  transform: rotate(-15deg);
}

.playing .arm {
  transform: rotate(15deg);
}

.ring {
  position: absolute;
  width: 224px;
  height: 224px;
  border-radius: 50%;
  background: rgba(139, 92, 246, 0.3);
  z-index: 1;
  animation: pulse 2s ease-out infinite;
}

.ring-2 {
  background: rgba(236, 72, 153, 0.2);
  animation-delay: 1s;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes pulse {
  0% {
    transform: scale(1);
    opacity: 0.8;
  }
  100% {
    transform: scale(1.5);
    opacity: 0;
  }
}
</style>
