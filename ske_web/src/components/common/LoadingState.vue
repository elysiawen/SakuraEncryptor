<template>
  <div class="loading-state">
    <n-spin size="large" />
    <p>{{ text }}</p>
  </div>
</template>

<script setup>
defineProps({
  text: { type: String, default: '正在加载…' },
})
</script>

<style scoped>
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 80px 24px;
  color: var(--text-secondary);
  font-size: 15px;
}
</style>
