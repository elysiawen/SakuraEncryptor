<!--
  One mounted share.

  A share is a token (the address Windows mounts), a credential pair, and one
  target. The target is what makes several of them worth having: the whole app,
  a single library, or a folder inside a particular cloud profile.

  Main is the authority on token rules; this form only keeps the button from
  inviting a submission that is guaranteed to bounce.
-->
<template>
  <AppDialog
    :visible="visible"
    :title="share ? $t('settings.dav.editShare') : $t('settings.dav.addShare')"
    :width="460"
    :busy="busy"
    :error="error"
    :layer="layer"
    @cancel="cancel"
    @submit="submit"
  >
    <div class="field">
      <label for="dav-share-name">{{ $t('settings.dav.name') }}</label>
      <input
        id="dav-share-name"
        v-model="form.name"
        :placeholder="$t('settings.dav.namePlaceholder')"
        autofocus
      />
    </div>

    <div class="field">
      <label for="dav-share-kind">{{ $t('settings.dav.kind') }}</label>
      <select id="dav-share-kind" v-model="form.kind">
        <option value="mixed">{{ $t('settings.dav.kindMixed') }}</option>
        <option value="local">{{ $t('settings.dav.kindLocal') }}</option>
        <option value="cloud">{{ $t('settings.dav.kindCloud') }}</option>
      </select>
    </div>

    <div v-if="form.kind === 'cloud'" class="field">
      <label for="dav-share-profile">{{ $t('settings.dav.profile') }}</label>
      <!-- Only real profiles, no placeholder row: a cloud share has to point at
           one, so the picker starts on a sensible choice instead of on "none". -->
      <select id="dav-share-profile" v-model="form.profileId" :disabled="!profiles.length">
        <option v-if="missingProfileId" :value="missingProfileId" disabled>
          {{ $t('settings.dav.profileMissingOption') }}
        </option>
        <option v-for="profile in profiles" :key="profile.id" :value="profile.id">
          {{ profile.name }}
        </option>
      </select>
      <div v-if="!profiles.length" class="meta">{{ $t('settings.dav.profileEmpty') }}</div>
      <div v-else-if="missingProfileId" class="meta">{{ $t('settings.dav.profileMissing') }}</div>
    </div>

    <div v-if="form.kind !== 'mixed'" class="field">
      <label for="dav-share-base">{{ $t('settings.dav.base') }}</label>
      <div class="row">
        <input id="dav-share-base" v-model.trim="form.base" placeholder="/Movies" class="mono" />
        <button
          class="ghost sm"
          type="button"
          :disabled="form.kind === 'cloud' && !form.profileId"
          @click="browseOpen = true"
        >
          {{ $t('settings.dav.browse') }}
        </button>
      </div>
      <div class="meta">{{ $t('settings.dav.baseHint') }}</div>
    </div>

    <div class="field">
      <label for="dav-share-token">{{ $t('settings.dav.token') }}</label>
      <div class="row">
        <input id="dav-share-token" v-model.trim="form.token" spellcheck="false" class="mono" />
        <button class="ghost sm" type="button" @click="roll">
          {{ $t('settings.dav.regenerate') }}
        </button>
      </div>
      <div class="meta">{{ $t('settings.dav.tokenHint') }}</div>
    </div>

    <!-- The credential pair. Both may be left empty: the app generates them,
         short enough to type into Finder. -->
    <div class="field">
      <label for="dav-share-user">{{ $t('settings.dav.shareUser') }}</label>
      <input id="dav-share-user" v-model.trim="form.username" spellcheck="false" placeholder="sakura" class="mono" />
      <div class="meta">{{ $t('settings.dav.shareUserHint') }}</div>
    </div>

    <div class="field">
      <label for="dav-share-pass">{{ $t('settings.dav.sharePassword') }}</label>
      <div class="row">
        <input id="dav-share-pass" v-model="form.password" spellcheck="false" class="mono" />
        <button class="ghost sm" type="button" @click="rollPassword">
          {{ $t('settings.dav.regenerate') }}
        </button>
      </div>
      <div class="meta">{{ $t('settings.dav.sharePasswordHint') }}</div>
    </div>

    <template #footer>
      <button class="ghost" type="button" :disabled="busy" @click="cancel">
        {{ $t('common.cancel') }}
      </button>
      <button class="primary" type="submit" :disabled="busy || !valid">
        {{ $t('common.save') }}
      </button>
    </template>

    <!-- Nested so the form keeps its state while the tree is walked. -->
    <DavBrowseDialog
      :visible="browseOpen"
      :kind="form.kind"
      :profile-id="form.profileId"
      :path="form.base || '/'"
      :layer="layer ? layer + 1 : 66"
      @cancel="browseOpen = false"
      @pick="pickFolder"
    />
  </AppDialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import DavBrowseDialog from './DavBrowseDialog.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** The share being edited, already in main's input shape; null creates one. */
  share: { type: Object, default: null },
  /** Cloud profiles to choose from — the vault list the settings page holds. */
  profiles: { type: Array, default: () => [] },
  /** The profile the app is browsing; a new cloud share starts there. */
  activeProfileId: { type: String, default: '' },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' },
  /** Stacking order, for a form opened on top of the account list. */
  layer: { type: Number, default: 0 },
})

const emit = defineEmits(['cancel', 'submit'])

/** The folder picker is a dialog on top of this one, not a replacement. */
const browseOpen = ref(false)

const form = reactive({
  id: '',
  name: '',
  kind: 'mixed',
  profileId: '',
  base: '',
  token: '',
  username: '',
  password: '',
})

function randomHex(bytes) {
  const buffer = new Uint8Array(bytes)
  crypto.getRandomValues(buffer)
  return Array.from(buffer, (byte) => byte.toString(16).padStart(2, '0')).join('')
}

/** Same shape main generates: 18 bytes, base64url, nothing to escape later. */
function randomSecret() {
  const buffer = new Uint8Array(18)
  crypto.getRandomValues(buffer)
  return btoa(String.fromCharCode(...buffer))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

/** Mirrors the rules in main, so an invalid value never leaves the dialog. */
const VALID_TOKEN = /^[A-Za-z0-9._~-]{8,64}$/
const VALID_USER = /^[A-Za-z0-9._~-]{1,32}$/

/** A profile the share points at that the vault no longer has, if any. */
const missingProfileId = computed(() => {
  if (form.kind !== 'cloud' || !form.profileId) return ''
  return props.profiles.some((profile) => profile.id === form.profileId) ? '' : form.profileId
})

const valid = computed(() => {
  if (!VALID_TOKEN.test(form.token)) return false
  if (form.username && !VALID_USER.test(form.username)) return false
  if (form.password.length < 8 || form.password.length > 128) return false
  if (form.kind === 'cloud' && !props.profiles.some((profile) => profile.id === form.profileId)) {
    return false
  }
  return true
})

watch(
  () => [props.visible, props.share],
  () => {
    if (!props.visible) return
    browseOpen.value = false
    // The id is the identity main keeps warm containers under, so a new share
    // gets one here: a re-open of the same dialog must not look like an edit.
    form.id = props.share?.id || randomHex(6)
    form.name = props.share?.name || ''
    form.kind = props.share?.kind || 'mixed'
    form.profileId = props.share?.profileId || ''
    form.base = props.share?.base || ''
    form.token = props.share?.token || randomHex(16)
    form.username = props.share?.username || ''
    // The live password, or a fresh one for a new share: either way it is what
    // will be in use after saving, so it is shown rather than hidden behind a
    // placeholder.
    form.password = props.share?.password || randomSecret()
  },
  { immediate: true },
)

/**
 * Switching to a cloud share picks a profile straight away.
 *
 * The kind cannot exist without one, so an empty select would be a state this
 * dialog has to explain rather than a choice the user can make. The profile the
 * app is currently browsing is the least surprising default.
 */
watch(
  () => form.kind,
  (kind) => {
    if (kind !== 'cloud' || form.profileId || !props.profiles.length) return
    const active = props.profiles.find((profile) => profile.id === props.activeProfileId)
    form.profileId = (active || props.profiles[0]).id
  },
)

function cancel() {
  if (props.busy) return
  emit('cancel')
}

function roll() {
  form.token = randomHex(16)
}

function rollPassword() {
  form.password = randomSecret()
}

/** `''` is the library root, which is exactly what an empty base path means. */
function pickFolder(path) {
  form.base = path === '/' ? '' : path
  browseOpen.value = false
}

function submit() {
  if (props.busy || !valid.value) return
  emit('submit', {
    id: form.id,
    name: form.name.trim(),
    token: form.token,
    username: form.username,
    password: form.password,
    kind: form.kind,
    profileId: form.kind === 'cloud' ? form.profileId : '',
    base: form.kind === 'mixed' ? '' : form.base,
  })
}
</script>
