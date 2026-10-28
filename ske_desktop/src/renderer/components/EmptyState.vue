<template>
  <div class="empty">
    <div class="mark">{{ icon }}</div>
    <h3>{{ title }}</h3>
    <p v-if="description">{{ description }}</p>
    <button v-if="actionText" class="ghost" type="button" @click="$emit('action')">
      {{ actionText }}
    </button>
    <slot />
  </div>
</template>

<script setup>
defineProps({
  icon: { type: String, default: '✦' },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  actionText: { type: String, default: '' },
})

defineEmits(['action'])
</script>

<style scoped>
.empty {
  height: 100%;
  min-height: 260px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  text-align: center;
  padding: 40px 24px;
  color: var(--text-dim);
}

.mark {
  font-size: 26px;
  opacity: 0.65;
  margin-bottom: 2px;
}

h3 {
  font-size: 14px;
  color: var(--text);
}

p {
  margin: 0;
  max-width: 460px;
  font-size: 12.5px;
  color: var(--muted);
  line-height: 1.7;
}

button {
  margin-top: 6px;
}
</style>
