<template>
  <div>
    <LoadingState v-if="loading" />
    <ErrorState v-else-if="error" :message="error" @retry="$emit('retry')" />
    <EmptyState
      v-else-if="items.length === 0"
      icon="📂"
      title="该目录为空"
    />
    <div
      v-else
      class="file-grid"
      :class="view === 'grid' ? 'file-grid--tiles' : 'file-grid--cards'"
    >
      <template v-if="view === 'grid'">
        <FileTile
          v-for="item in items"
          :key="item.encName || item.path"
          :item="item"
          :mode="mode"
          :parent-path="parentPath"
          @click="$emit('click', item)"
          @contextmenu="(e, it) => $emit('contextmenu', e, it)"
        />
      </template>
      <template v-else>
        <FileCard
          v-for="item in items"
          :key="item.encName || item.path"
          :item="item"
          @click="$emit('click', item)"
          @contextmenu="(e, it) => $emit('contextmenu', e, it)"
        />
      </template>
    </div>
  </div>
</template>

<script setup>
import FileCard from './FileCard.vue'
import FileTile from './FileTile.vue'
import LoadingState from '../common/LoadingState.vue'
import ErrorState from '../common/ErrorState.vue'
import EmptyState from '../common/EmptyState.vue'

defineProps({
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  /** 'list' — the card layout; 'grid' — square thumbnails. */
  view: { type: String, default: 'list' },
  mode: { type: String, default: 'alist' },
  parentPath: { type: String, default: '/' },
})

defineEmits(['click', 'retry', 'contextmenu'])
</script>

<style scoped>
.file-grid {
  display: grid;
  gap: 12px;
}

.file-grid--cards {
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
}

.file-grid--tiles {
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
}

@media (max-width: 600px) {
  .file-grid--cards {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .file-grid--tiles {
    grid-template-columns: repeat(auto-fill, minmax(108px, 1fr));
    gap: 8px;
  }
}
</style>
