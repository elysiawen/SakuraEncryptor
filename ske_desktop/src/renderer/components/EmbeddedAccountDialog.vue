<!--
  The local server's own admin account.
  Separate from the media password: this one only guards the loopback API.
-->
<template>
  <AppDialog
    :visible="visible"
    :title="$t('embedded.accountTitle')"
    :description="$t('embedded.accountDesc')"
    :width="460"
    :busy="busy"
    @cancel="close"
  >
    <p v-if="state?.status !== 'running'" class="hint">{{ $t('embedded.accountOffline') }}</p>

    <template v-else>
      <div class="field">
        <label for="embedded-user">{{ $t('embedded.username') }}</label>
        <input id="embedded-user" v-model="draft.username" type="text" placeholder="admin" />
        <div class="meta">
          {{ $t('embedded.usernameHint', { name: embedded.account.username || 'admin' }) }}
        </div>
      </div>

      <div class="field">
        <label for="embedded-pass">{{ $t('embedded.accountPassword') }}</label>
        <input
          id="embedded-pass"
          v-model="draft.password"
          type="text"
          :placeholder="$t('embedded.keepEmpty')"
        />
        <div class="row inline">
          <button class="ghost sm" type="button" @click="reveal">
            {{ $t('embedded.reveal') }}
          </button>
          <button class="ghost sm" type="button" @click="copy">{{ $t('common.copy') }}</button>
          <button class="ghost sm" type="button" @click="roll">{{ $t('embedded.generate') }}</button>
        </div>
        <div class="meta">
          {{ revealed ? $t('embedded.currentPassword', { value: revealed }) : $t('embedded.passwordManaged') }}
        </div>
      </div>
    </template>

    <template #footer>
      <button class="ghost" type="button" @click="close">{{ $t('common.close') }}</button>
      <button class="primary" type="button" :disabled="busy || !changed" @click="save">
        {{ $t('embedded.save') }}
      </button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import { t } from '../i18n.js'
import { toast } from '../state/session.js'
import {
  embedded,
  loadAccount,
  loadEmbedded,
  randomEmbeddedPassword,
  revealEmbeddedPassword,
  updateEmbeddedAccount,
} from '../state/embedded.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
})

const emit = defineEmits(['close'])

const state = computed(() => embedded.state)
const busy = computed(() => embedded.busy)
const revealed = computed(() => embedded.revealedPassword)

const draft = reactive({ username: '', password: '' })

const changed = computed(() => {
  const name = draft.username.trim()
  return (!!name && name !== embedded.account.username) || !!draft.password
})

function close() {
  draft.password = ''
  embedded.revealedPassword = ''
  emit('close')
}

async function reveal() {
  await revealEmbeddedPassword()
}

async function copy() {
  const password = embedded.revealedPassword || (await revealEmbeddedPassword())
  if (!password) return
  try {
    await navigator.clipboard.writeText(password)
    toast(t('embedded.passwordCopied'), 'ok')
  } catch {
    toast(t('embedded.copyFailed'), 'error')
  }
}

async function roll() {
  const password = await randomEmbeddedPassword()
  if (password) draft.password = password
}

async function save() {
  const payload = {}
  const name = draft.username.trim()
  if (name && name !== embedded.account.username) payload.username = name
  if (draft.password) payload.password = draft.password
  if (!payload.username && !payload.password) return
  const ok = await updateEmbeddedAccount(payload)
  if (ok) {
    draft.password = ''
    await loadAccount()
    draft.username = embedded.account.username
    close()
  }
}

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) return
    await loadEmbedded()
    if (embedded.state?.status === 'running') await loadAccount()
    draft.username = embedded.account.username
    draft.password = ''
  },
  { immediate: true },
)
</script>

<style scoped>
.row.inline {
  margin-top: 8px;
}
</style>
