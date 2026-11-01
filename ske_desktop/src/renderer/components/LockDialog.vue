<!--
  Everything that changes the launch gate happens here, not inline in settings.

  One dialog for three jobs — set/change the password, bind the TOTP token,
  turn the whole lock off — because they share the same shell and the same
  rule: the form collects proofs, the main process decides whether they are
  enough.
-->
<template>
  <AppDialog
    :visible="visible"
    :title="title"
    :description="description"
    :busy="busy"
    :error="error"
    :width="440"
    @cancel="cancel"
    @submit="submit"
  >
    <template v-if="mode === 'password'">
      <input
        v-if="hasPassword"
        ref="first"
        v-model="currentPassword"
        type="password"
        autocomplete="off"
        :placeholder="$t('lock.currentPassword')"
      />
      <input
        ref="passwordField"
        v-model="password"
        type="password"
        autocomplete="new-password"
        :placeholder="$t('lock.newPasswordHint', { min: minPassword })"
      />
    </template>

    <template v-else-if="mode === 'totp'">
      <p class="dlg-desc">{{ $t('lock.totpScanHint') }}</p>

      <!-- The default path is "take this new key". Reusing a token the user
           already registered is the exception, so it lives behind a link
           instead of sitting in the middle of the form looking mandatory. -->
      <template v-if="!useExisting">
        <div class="mono secret">{{ secret || $t('lock.generatingSecret') }}</div>
        <div v-if="uri" class="mono uri" :title="uri">{{ uri }}</div>
      </template>

      <template v-else>
        <input
          ref="pastedField"
          v-model="pasted"
          class="mono"
          autocomplete="off"
          :placeholder="$t('lock.pasteSecret')"
        />
        <p class="hint">{{ $t('lock.existingHint') }}</p>
      </template>

      <input
        ref="first"
        v-model="code"
        class="code"
        inputmode="numeric"
        maxlength="6"
        :placeholder="$t('lock.codePlaceholder')"
        @input="onCodeInput"
      />

      <div class="totp-foot">
        <button
          v-if="!useExisting"
          class="ghost sm"
          type="button"
          @click="emit('regenerate')"
        >
          {{ $t('lock.regenerate') }}
        </button>
        <span />
        <button class="link" type="button" @click="toggleExisting">
          {{ useExisting ? $t('lock.useNew') : $t('lock.alreadyHave') }}
        </button>
      </div>
    </template>

    <template v-else>
      <input
        v-if="hasPassword"
        ref="first"
        v-model="currentPassword"
        type="password"
        autocomplete="off"
        :placeholder="$t('lock.currentPassword')"
      />
      <input
        v-if="hasTotp"
        v-model="code"
        class="code"
        inputmode="numeric"
        maxlength="6"
        :placeholder="$t('lock.codePlaceholder')"
        @input="onCodeInput"
      />
    </template>

    <template #footer>
      <button class="ghost" type="button" :disabled="busy" @click="cancel">
        {{ $t('common.cancel') }}
      </button>
      <button
        :class="mode === 'disable' ? 'danger' : 'primary'"
        type="submit"
        :disabled="busy || !canSubmit"
      >
        {{ busy ? $t('common.processing') : confirmText }}
      </button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import { t } from '../i18n.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** password | totp | disable */
  mode: { type: String, default: 'password' },
  hasPassword: { type: Boolean, default: false },
  hasTotp: { type: Boolean, default: false },
  minPassword: { type: Number, default: 6 },
  /** Proposed secret and its uri, filled by the parent for `mode === 'totp'`. */
  secret: { type: String, default: '' },
  uri: { type: String, default: '' },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

const emit = defineEmits(['cancel', 'submit', 'regenerate'])

const currentPassword = ref('')
const password = ref('')
const code = ref('')
const pasted = ref('')
/** Whether the TOTP form is bound to a key the user already has. */
const useExisting = ref(false)
const first = ref(null)
const passwordField = ref(null)
const pastedField = ref(null)

const title = computed(() => {
  if (props.mode === 'totp') return t('lock.titleTotp')
  if (props.mode === 'disable') return t('lock.titleDisable')
  return props.hasPassword ? t('lock.titleChangePassword') : t('lock.titleSetPassword')
})

const description = computed(() => {
  if (props.mode === 'disable') {
    const factors = [props.hasPassword ? t('lock.factorPassword') : '', props.hasTotp ? 'TOTP' : '']
      .filter(Boolean)
      .join(t('lock.factorJoin'))
    return t('lock.disableWarning', { factors })
  }
  if (props.mode === 'password' && props.hasPassword) {
    return t('lock.passwordChangedHint')
  }
  return ''
})

const confirmText = computed(() => {
  if (props.mode === 'totp') return t('lock.confirmBind')
  if (props.mode === 'disable') return t('lock.titleDisable')
  return props.hasPassword ? t('lock.confirmChange') : t('lock.confirmEnable')
})

const canSubmit = computed(() => {
  if (props.mode === 'totp') {
    return code.value.length === 6 && (!useExisting.value || !!pasted.value.trim())
  }
  if (props.mode === 'disable') {
    return (!props.hasPassword || !!currentPassword.value) && (!props.hasTotp || code.value.length === 6)
  }
  return password.value.length >= props.minPassword && (!props.hasPassword || !!currentPassword.value)
})

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) return
    currentPassword.value = ''
    password.value = ''
    code.value = ''
    pasted.value = ''
    useExisting.value = false
    await nextTick()
    // On a first-time password there is no current-password field to land on.
    if (props.mode === 'password' && !props.hasPassword) passwordField.value?.focus()
    else first.value?.focus()
  },
  { immediate: true },
)

function onCodeInput(event) {
  const digits = String(event.target.value || '').replace(/\D/g, '').slice(0, 6)
  code.value = digits
  event.target.value = digits
}

async function toggleExisting() {
  useExisting.value = !useExisting.value
  if (!useExisting.value) pasted.value = ''
  await nextTick()
  // Keep the caret on the field that just became relevant instead of leaving it
  // on a link the user has already used.
  if (useExisting.value) pastedField.value?.focus()
  else first.value?.focus()
}

function cancel() {
  if (props.busy) return
  emit('cancel')
}

function submit() {
  if (props.busy || !canSubmit.value) return
  if (props.mode === 'totp') {
    // A pasted key wins: the user brought their own token and is proving it.
    emit('submit', { code: code.value, secret: pasted.value.trim() || props.secret })
    return
  }

  if (props.mode === 'disable') {
    // This mode has no "new password" field: the only password on screen is the
    // one being verified, and sending `password` would always send an empty
    // string — the main process would reject a correct password.
    emit('submit', { password: currentPassword.value, totp: code.value })
    return
  }

  emit('submit', {
    password: password.value,
    currentPassword: currentPassword.value,
    totp: code.value,
  })
}
</script>

<style scoped>
.secret {
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  font-size: 13px;
  letter-spacing: 0.08em;
  text-align: center;
  word-break: break-all;
  user-select: all;
}

.uri {
  font-size: 11px;
  color: var(--muted);
  overflow-wrap: anywhere;
}

/* A one-time code is read digit by digit, so widen the tracking. */
.code {
  font-family: var(--mono);
  letter-spacing: 0.35em;
  text-align: center;
  font-size: 15px;
}

.hint {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--muted);
}

/* The escape hatch to an existing token reads as a link, not as another
   button: it must stay a footnote to the main "bind this key" flow. */
.link {
  padding: 0;
  border: 0;
  background: none;
  font-size: 11.5px;
  color: var(--muted);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.link:hover:not(:disabled) {
  background: none;
  color: var(--accent-strong);
}

.totp-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
</style>
