<template>
  <n-tag :type="tagType" size="small" round :bordered="false" :class="'tag-' + type">
    {{ label }}
  </n-tag>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  // folder, video, image, audio, subtitle, file, encrypted, plain
  type: { type: String, required: true },
})

const { t } = useI18n()

const labelKeys = {
  folder: 'tag.folder',
  video: 'tag.video',
  image: 'tag.image',
  audio: 'tag.audio',
  subtitle: 'tag.subtitle',
  file: 'tag.file',
  encrypted: 'tag.encrypted',
  plain: 'tag.plain',
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

const label = computed(() => t(labelKeys[props.type] || 'tag.file'))
const tagType = computed(() => tagTypeMap[props.type] || 'default')
</script>
