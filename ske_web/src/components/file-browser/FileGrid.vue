<template>
  <div>
    <LoadingState v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="$emit('retry')" />
    <EmptyState
      v-else-if="items.length === 0"
      icon="📂"
      title="该目录为空"
    />
    <div v-else class="file-grid">
      <FileCard
        v-for="item in items"
        :key="item.encName || item.path"
        :item="item"
        @click="$emit('click', item)"
        @contextmenu="(e, it) => $emit('contextmenu', e, it)"
      />
    </div>
  </div>
</template>

<script setup>
import FileCard from './FileCard.vue'
import LoadingState from '../common/LoadingState.vue'
import ErrorState from '../common/ErrorState.vue'
import EmptyState from '../common/EmptyState.vue'

defineProps({
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

defineEmits(['click', 'retry', 'contextmenu'])
</script>

<style scoped>
.file-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}

@media (max-width: 600px) {
  .file-grid {
    grid-template-columns: 1fr;
    gap: 8px;
  }
}
</style>
