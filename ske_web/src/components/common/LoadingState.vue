<template>
  <div class="loading-state">
    <n-spin size="large" />
    <p>{{ text || t('common.loading') }}</p>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'

defineProps({
  text: { type: String, default: '' },
})

const { t } = useI18n()
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
