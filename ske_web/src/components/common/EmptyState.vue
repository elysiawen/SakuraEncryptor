<template>
  <div class="empty-state">
    <div class="empty-icon">{{ icon }}</div>
    <h3>{{ title || t('common.empty') }}</h3>
    <p v-if="description">{{ description }}</p>
    <n-button v-if="actionText" type="primary" @click="$emit('action')">{{ actionText }}</n-button>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'

defineProps({
  icon: { type: String, default: '📂' },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  actionText: { type: String, default: '' },
})

defineEmits(['action'])

const { t } = useI18n()
</script>

<style scoped>
.empty-state {
  text-align: center;
  padding: 80px 24px;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 24px;
  opacity: 0.3;
}

.empty-state h3 {
  font-size: 20px;
  margin-bottom: 8px;
}

.empty-state p {
  color: var(--text-muted);
  margin-bottom: 32px;
}
</style>
