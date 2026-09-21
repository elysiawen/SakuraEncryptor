<template>
  <n-tag :type="tagType" size="small" round :bordered="false" :class="'tag-' + type">
    {{ label }}
  </n-tag>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  // folder, video, image, audio, subtitle, file, encrypted, plain
  type: { type: String, required: true },
})

const labelMap = {
  folder: '文件夹',
  video: '视频',
  image: '图片',
  audio: '音乐',
  subtitle: '字幕',
  file: '文件',
  encrypted: '加密',
  plain: '未加密',
}

const tagTypeMap = {
  folder: 'info',
  video: 'info',
  image: 'success',
  audio: 'error',
  subtitle: 'warning',
  file: 'default',
  encrypted: 'info',
  plain: 'default',
}

const label = computed(() => labelMap[props.type] || '文件')
const tagType = computed(() => tagTypeMap[props.type] || 'default')
</script>
