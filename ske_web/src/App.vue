<template>
  <n-config-provider
    :theme="darkTheme"
    :theme-overrides="themeOverrides"
    :locale="naiveLocale"
    :date-locale="naiveDateLocale"
  >
    <n-message-provider>
      <UpstreamAlert />
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
      <DownloadManager />
    </n-message-provider>
  </n-config-provider>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { darkTheme, zhCN, dateZhCN, enUS, dateEnUS } from 'naive-ui'
import { themeOverrides } from './main.js'
import DownloadManager from './components/common/DownloadManager.vue'
import UpstreamAlert from './components/common/UpstreamAlert.vue'

const { locale } = useI18n()

// Naive UI ships its own strings (empty tables, pagination, date pickers), so it
// has to follow the app language instead of staying Chinese.
const naiveLocale = computed(() => (locale.value.startsWith('zh') ? zhCN : enUS))
const naiveDateLocale = computed(() => (locale.value.startsWith('zh') ? dateZhCN : dateEnUS))
</script>
