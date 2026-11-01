<template>
  <div class="error-state">
    <n-alert type="error" :bordered="false" style="max-width: 400px;">
      {{ message || t('common.loadFailed') }}
    </n-alert>
    <n-button quaternary @click="$emit('retry')">{{ t('common.retry') }}</n-button>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'

defineProps({
  message: { type: String, default: '' },
})

defineEmits(['retry'])

const { t } = useI18n()
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
