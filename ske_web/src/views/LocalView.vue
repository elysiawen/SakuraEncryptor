<template>
  <DefaultLayout show-breadcrumbs>
    <template #default>
      <FileBrowser mode="local" :local-state="state" />
    </template>
  </DefaultLayout>
</template>

<script setup>
import DefaultLayout from '../layouts/DefaultLayout.vue'
import FileBrowser from '../components/file-browser/FileBrowser.vue'
import { useLocalFiles } from '../composables/useLocalFiles.js'

const { state } = useLocalFiles()
</script>
