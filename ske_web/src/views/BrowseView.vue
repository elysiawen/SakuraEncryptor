<template>
  <DefaultLayout :show-breadcrumbs="true">
    <FileBrowser :mode="provider" :list-fn="listDir" />
  </DefaultLayout>
</template>

<script setup>
import DefaultLayout from '../layouts/DefaultLayout.vue'
import FileBrowser from '../components/file-browser/FileBrowser.vue'
import { listDir, getProvider } from '../composables/useRemoteStorage.js'

const provider = getProvider()
</script>
