<template>
  <div class="media-layout">
    <slot />
  </div>
</template>

<style scoped>
.media-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
</style>
