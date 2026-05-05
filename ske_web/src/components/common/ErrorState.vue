<template>
  <div class="error-state">
    <n-alert type="error" :bordered="false" style="max-width: 400px;">
      {{ message }}
    </n-alert>
    <n-button quaternary @click="$emit('retry')">重试</n-button>
  </div>
</template>

<script setup>
defineProps({
  message: { type: String, default: '加载失败' },
})

defineEmits(['retry'])
</script>

<style scoped>
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 80px 24px;
}
</style>
