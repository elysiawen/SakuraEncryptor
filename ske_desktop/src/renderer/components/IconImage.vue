<!--
  Line-art marker for images.

  The 🖼 emoji reads as a framed postcard at 15 px and fights the artwork behind
  it in the grid, so images get a drawn icon instead. It is sized in `em` and
  stroked with `currentColor` to inherit whatever placeholder/row style it lands
  in. Other kinds keep their emoji.
-->
<template>
  <svg
    class="icon-image"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <rect x="3.2" y="4.8" width="17.6" height="14.4" rx="2.6" />
    <circle cx="8.6" cy="10.2" r="1.5" />
    <path d="M20.4 15.8 15.7 11 6.2 19.2" />
  </svg>
</template>

<style scoped>
.icon-image {
  width: 1em;
  height: 1em;
  /* Nudged so an inline icon sits on the same optical line as the emoji. */
  vertical-align: -0.12em;
}
</style>
