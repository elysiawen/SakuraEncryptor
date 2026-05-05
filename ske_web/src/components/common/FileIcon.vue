<template>
  <span class="file-icon">{{ icon }}</span>
</template>

<script setup>
import { computed } from 'vue'
import { getFileIcon } from '../../composables/useFileDetection.js'

const props = defineProps({
  item: { type: Object, required: true },
})

const icon = computed(() => getFileIcon(props.item))
</script>

<style scoped>
.file-icon {
  font-size: 28px;
  flex-shrink: 0;
  width: 40px;
  text-align: center;
}
</style>
