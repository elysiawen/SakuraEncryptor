<template>
  <DefaultLayout :show-breadcrumbs="true">
    <FileBrowser mode="alist" :list-fn="listDir" />
  </DefaultLayout>
</template>

<script setup>
import DefaultLayout from '../layouts/DefaultLayout.vue'
import FileBrowser from '../components/file-browser/FileBrowser.vue'
import { listDir } from '../composables/useAList.js'
</script>
