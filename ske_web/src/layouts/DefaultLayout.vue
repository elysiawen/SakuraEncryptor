<template>
  <div class="default-layout">
    <AppTopbar v-if="showTopbar" :show-breadcrumbs="showBreadcrumbs" />
    <main class="layout-content">
      <slot />
    </main>
  </div>
</template>

<script setup>
import AppTopbar from '../components/common/AppTopbar.vue'

defineProps({
  showBreadcrumbs: { type: Boolean, default: false },
  showTopbar: { type: Boolean, default: true },
})
</script>

<style scoped>
.default-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow-y: auto;
}

.layout-content {
  flex: 1;
  padding: 16px 16px 24px;
}

@media (max-width: 600px) {
  .layout-content {
    padding: 8px 8px 16px;
  }
}
</style>
