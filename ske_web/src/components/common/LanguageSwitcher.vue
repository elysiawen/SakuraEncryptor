<template>
  <n-dropdown trigger="click" :options="options" :show-arrow="true" @select="onSelect">
    <n-button quaternary size="small" :title="t('common.language')">
      <span class="lang-icon">🌐</span>
      <span v-if="!iconOnly" class="lang-label">{{ currentLabel }}</span>
    </n-button>
  </n-dropdown>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { SUPPORTED_LOCALES, setLocale } from '../../i18n/index.js'

defineProps({
  /** Only the globe glyph — for cramped toolbars. */
  iconOnly: { type: Boolean, default: false },
})

const { t, locale } = useI18n()

// Native names on purpose: the language list must be readable no matter which
// language is active right now.
const options = SUPPORTED_LOCALES.map((item) => ({
  key: item.value,
  label: item.label,
}))

const currentLabel = computed(
  () => SUPPORTED_LOCALES.find((item) => item.value === locale.value)?.label || '',
)

function onSelect(value) {
  setLocale(value)
}
</script>

<style scoped>
.lang-icon {
  font-size: 14px;
  line-height: 1;
}

.lang-label {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 600;
}
</style>
