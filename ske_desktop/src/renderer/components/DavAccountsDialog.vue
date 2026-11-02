<!--
  The account list for the mounted folder.

  Each account is its own address, its own credential pair and its own target, so
  this list is where "what am I exposing right now" gets answered. It lives in a
  dialog rather than on the settings card: the card is a switch and a port, and a
  list that grows with every account belongs behind a button.

  Everything here is derived from what main reports — addresses, generated
  passwords, readiness — so a refresh always tells the truth.
-->
<template>
  <AppDialog
    :visible="visible"
    :title="$t('settings.dav.accountsTitle')"
    :description="$t('settings.dav.accountsDesc')"
    :width="560"
    :busy="busy"
    @cancel="cancel"
  >
    <!-- In the title row rather than the body: it is how this list grows, and a
         button at the end of the list reads as "confirm" to whoever got there. -->
    <template #header>
      <button class="primary sm" type="button" :disabled="busy" @click="addShare">
        {{ $t('settings.dav.addShare') }}
      </button>
    </template>

    <p v-if="!accounts.length" class="hint">{{ $t('settings.dav.accountsEmpty') }}</p>

    <div v-for="share in accounts" :key="share.id" class="dav-share">
      <div class="dav-share-head">
        <strong>{{ share.name }}</strong>
        <span class="badge" :class="{ warn: !share.ready }">{{ shareState(share) }}</span>
      </div>

      <div class="dav-facts">
        <div class="fact">
          <span>{{ $t('settings.dav.url') }}</span>
          <span class="mono path" :title="share.url">
            {{ share.url || $t('settings.dav.notListening') }}
          </span>
        </div>
        <div class="fact">
          <span>{{ $t('settings.dav.account') }}</span>
          <span class="mono">{{ share.username }} / {{ share.password }}</span>
        </div>
        <div class="fact">
          <span>{{ $t('settings.dav.kind') }}</span>
          <span>{{ shareTarget(share) }}</span>
        </div>
      </div>

      <div class="row" style="margin-top: 10px">
        <button class="ghost sm" type="button" @click="editShare(share)">
          {{ $t('common.edit') }}
        </button>
        <!-- The last account stays: main refuses an empty list, so the button says
             so instead of letting the user find out by error toast. -->
        <button
          class="ghost sm"
          type="button"
          :disabled="accounts.length <= 1"
          @click="askRemove(share)"
        >
          {{ $t('settings.dav.remove') }}
        </button>
        <button class="primary sm" type="button" :disabled="!share.url" @click="openShare(share)">
          {{ $t('settings.dav.open') }}
        </button>
      </div>
    </div>

    <template #footer>
      <button class="ghost" type="button" :disabled="busy" @click="cancel">
        {{ $t('common.close') }}
      </button>
    </template>

    <!-- Nested so an edit keeps this list mounted: closing the form returns here
         rather than to the settings page. -->
    <DavShareDialog
      :visible="editor.open"
      :share="editor.share"
      :profiles="profiles"
      :active-profile-id="activeProfileId"
      :busy="editor.busy"
      :error="editor.error"
      :layer="66"
      @cancel="closeEditor"
      @submit="saveShare"
    />

    <ConfirmDialog
      :visible="!!removeTarget"
      :title="$t('settings.dav.removeTitle')"
      :description="removeTarget ? $t('settings.dav.removeDesc', { name: removeTarget.name }) : ''"
      :busy="removing"
      :error="removeError"
      :layer="67"
      @cancel="cancelRemove"
      @confirm="confirmRemove"
    />
  </AppDialog>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import AppDialog from './AppDialog.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import DavShareDialog from './DavShareDialog.vue'
import { t } from '../i18n.js'
import { toast } from '../state/session.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** Live accounts from main: address, credentials, target and readiness. */
  accounts: { type: Array, default: () => [] },
  /** Cloud profiles, for the "cloud profile" target of an account. */
  profiles: { type: Array, default: () => [] },
  activeProfileId: { type: String, default: '' },
})

const emit = defineEmits(['cancel', 'changed'])

/** `ipcMain.handle` reports failures as `{ ok: false, error }` rather than throwing. */
function unwrap(res) {
  if (res?.ok === false) throw new Error(res.error || t('errors.operationFailed'))
  return res
}

/** Only the fields main accepts; the rest of an account is derived at runtime. */
function toShareInput(share) {
  return {
    id: share.id,
    name: share.name,
    token: share.token,
    kind: share.kind,
    profileId: share.profileId,
    base: share.base,
  }
}

/**
 * Hand the whole list back to main, which owns the rules and the file.
 *
 * Main replaces the list wholesale, so every account that is not being changed
 * is passed through as it is. The answer is the new truth, and the settings page
 * keeps the copy it shows.
 */
async function persist(accounts) {
  const res = unwrap(await window.ske.webdav.update({ accounts }))
  emit('changed', res)
  return res
}

const editor = reactive({ open: false, share: null, busy: false, error: '' })
const removeTarget = ref(null)
const removing = ref(false)
const removeError = ref('')

/** Removing an account is the only account-level request: it holds the dialog. */
const busy = computed(() => removing.value)

function addShare() {
  editor.share = null
  editor.error = ''
  editor.open = true
}

function editShare(share) {
  // The live account, credentials included: the form pre-fills them so they can
  // be changed in place, and an untouched password stays as it is.
  editor.share = share
  editor.error = ''
  editor.open = true
}

function closeEditor() {
  if (editor.busy) return
  editor.open = false
  editor.error = ''
}

function cancel() {
  if (removing.value || editor.busy) return
  emit('cancel')
}

async function saveShare(draft) {
  if (editor.busy) return
  editor.busy = true
  editor.error = ''
  try {
    const others = props.accounts.filter((item) => item.id !== draft.id).map(toShareInput)
    await persist([...others, draft])
    toast(t('settings.dav.shareSaved'), 'ok')
    editor.open = false
    editor.share = null
  } catch (err) {
    editor.error = err?.message || t('errors.operationFailed')
  } finally {
    editor.busy = false
  }
}

function askRemove(share) {
  removeError.value = ''
  removeTarget.value = share
}

function cancelRemove() {
  if (removing.value) return
  removeTarget.value = null
  removeError.value = ''
}

async function confirmRemove() {
  const share = removeTarget.value
  if (!share || removing.value) return
  removing.value = true
  removeError.value = ''
  try {
    const rest = props.accounts.filter((item) => item.id !== share.id).map(toShareInput)
    await persist(rest)
    removeTarget.value = null
    toast(t('settings.dav.shareRemoved'), 'ok')
  } catch (err) {
    // The confirmation stays open: the account is still there, and the reason
    // belongs next to the button that tried.
    removeError.value = err?.message || t('errors.operationFailed')
  } finally {
    removing.value = false
  }
}

/**
 * The reasons main reports, as message keys.
 *
 * Spelled out rather than interpolated: an audit can only see keys that appear
 * literally, and a missing translation is exactly what an audit is for.
 */
const SHARE_REASONS = {
  localLocked: 'settings.dav.localLocked',
  noProfile: 'settings.dav.noProfile',
  profileMissing: 'settings.dav.profileMissing',
  profileLocked: 'settings.dav.profileLocked',
}

/** Why an account cannot answer, in words; main only sends the reason key. */
function shareState(share) {
  if (share.ready) return t('settings.dav.ready')
  return t(SHARE_REASONS[share.reason] || 'settings.dav.localLocked')
}

function shareTarget(share) {
  if (share.kind === 'mixed') return t('settings.dav.kindMixed')
  const base = share.base || t('settings.dav.rootPath')
  if (share.kind === 'local') return `${t('settings.dav.kindLocal')} · ${base}`
  const profile = props.profiles.find((item) => item.id === share.profileId)
  const name = profile?.name || t('settings.dav.profileMissing')
  return `${t('settings.dav.kindCloud')} · ${name} · ${base}`
}

async function openShare(share) {
  const result = await window.ske.webdav.open(share.id)
  // Only Windows can be handed a UNC path directly; elsewhere the address above
  // is what the user connects to.
  if (!result?.opened) toast(t('settings.dav.openManual'), 'error')
}
</script>

<style scoped>
/* `.sm` is defined per view in this app, and a dialog has no view of its own:
   this is the compact size the buttons here were always written against. */
.sm {
  padding: 5px 10px;
  font-size: 12px;
  flex: 0 0 auto;
}

/* One account: a card inside the dialog, because each is a thing you configure
   and forget, and a flat list of key/value pairs read as one long paragraph. */
.dav-share {
  padding: 12px 14px;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
}

.dav-share + .dav-share {
  margin-top: 10px;
}

.dav-share-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.dav-facts {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.fact {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--text-dim);
}

.fact > span:first-child {
  width: 70px;
  flex: 0 0 70px;
  color: var(--muted);
}

.path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Three actions on one line is more than a narrow dialog can hold. */
.dav-share .row {
  flex-wrap: wrap;
}
</style>
