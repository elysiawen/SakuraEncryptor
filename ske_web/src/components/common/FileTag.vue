<template>
  <n-tag :type="tagType" size="small" round :bordered="false" :class="'tag-' + type">
    {{ label }}
  </n-tag>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  type: { type: String, required: true }, // folder, video, image, music, file
})

const labelMap = {
  folder: '文件夹',
  video: '视频',
  image: '图片',
  audio: '音乐',
  file: '文件',
}

const tagTypeMap = {
  folder: 'info',
  video: 'info',
  image: 'success',
  audio: 'error',
  file: 'default',
}

const label = computed(() => labelMap[props.type] || '文件')
const tagType = computed(() => tagTypeMap[props.type] || 'default')
</script>
