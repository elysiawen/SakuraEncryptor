<!--
  The one modal shell every dialog in the app is built from.

  It owns the parts that must not drift between dialogs: the overlay, the
  panel, the title/description rhythm, the error line, the footer row, and the
  ways out (backdrop click, Escape). Enter submits, because the root is a
  form — a caller that must not act on Enter simply does not listen for
  `submit`.

  Callers keep their own props and events; this is layout and behaviour, not
  state. Nothing here knows what the dialog is for.
-->
<template>
  <Teleport to="body">
    <form
      v-if="visible"
      class="dlg-overlay"
      @mousedown.self="cancel"
      @submit.prevent="submit"
    >
      <div
        class="dlg"
        :style="{ width: `${width}px` }"
        role="dialog"
        aria-modal="true"
        :aria-label="title || undefined"
      >
        <h2 v-if="title">{{ title }}</h2>
        <p v-if="description" class="dlg-desc">{{ description }}</p>

        <!-- Only the body scrolls: on a short window a long form must not drag
             the title and the buttons out of reach. -->
        <div class="dlg-body">
          <slot />
        </div>

        <p v-if="error" class="dlg-error">{{ error }}</p>

        <footer v-if="$slots.footer" class="dlg-actions">
          <slot name="footer" />
        </footer>
      </div>
    </form>
  </Teleport>
</template>

<script setup>
import { onBeforeUnmount, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  error: { type: String, default: '' },
  /** Blocks backdrop, Escape and submit while a request is in flight. */
  busy: { type: Boolean, default: false },
  width: { type: Number, default: 400 },
  /**
   * Off for destructive confirmations: losing a modal by a stray click next to
   * it is a bad way to discover the backdrop closes things.
   */
  dismissible: { type: Boolean, default: true },
})

const emit = defineEmits(['cancel', 'submit'])

function cancel() {
  if (props.busy || !props.dismissible) return
  emit('cancel')
}

function submit() {
  if (props.busy) return
  emit('submit')
}

/**
 * Escape is watched on the window, not on the form.
 *
 * A keydown only bubbles to the panel while focus is inside it; after a click
 * on the backdrop margin or on a label there is no focused child, and the
 * dialog would ignore the key that is supposed to close it.
 */
function onKeydown(event) {
  if (event.key !== 'Escape') return
  event.preventDefault()
  cancel()
}

watch(
  () => props.visible,
  (visible) => {
    if (visible) window.addEventListener('keydown', onKeydown)
    else window.removeEventListener('keydown', onKeydown)
  },
  { immediate: true },
)

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>
