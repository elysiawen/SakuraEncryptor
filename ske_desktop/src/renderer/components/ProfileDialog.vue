<template>
  <AppDialog
    :visible="visible"
    :title="profile ? $t('profiles.dialogTitleEdit') : $t('profiles.dialogTitleNew')"
    :description="$t('profiles.dialogDesc')"
    :width="460"
    :busy="busy"
    :error="error"
    @cancel="close"
    @submit="submit"
  >
    <div class="field">
      <label>{{ $t('profiles.nameField') }}</label>
      <input v-model="form.name" :placeholder="$t('profiles.namePlaceholder')" autofocus />
    </div>

    <div class="field">
      <label>{{ $t('profiles.providerField') }}</label>
      <select v-model="form.provider">
        <option value="alist">AList</option>
        <option value="openlist">OpenList</option>
      </select>
      <!-- 2FA codes are asked for on every login, because a one-time code
           cannot be stored. Both backends support it. -->
      <span class="meta">{{ $t('profiles.otpHint') }}</span>
    </div>

    <div class="field">
      <label>{{ $t('profiles.serverField') }}</label>
      <input v-model="form.server" :placeholder="serverPlaceholder" />
    </div>

    <div class="field">
      <label>{{ $t('profiles.usernameField') }}</label>
      <input
        v-model="form.username"
        :placeholder="$t('profiles.usernamePlaceholder', { provider: providerLabel })"
        autocomplete="off"
      />
    </div>

    <div class="field">
      <label>{{ $t('profiles.passwordField', { provider: providerLabel }) }}</label>
      <input
        v-model="form.password"
        type="password"
        autocomplete="new-password"
        :placeholder="
          profile?.hasPassword
            ? $t('profiles.savedKeep')
            : $t('profiles.passwordPlaceholder', { provider: providerLabel })
        "
      />
      <span class="meta">{{ $t('profiles.passwordHint') }}</span>
    </div>

    <div class="field">
      <label>{{ $t('profiles.skePasswordField') }}</label>
      <input
        v-model="form.skePassword"
        type="password"
        autocomplete="new-password"
        :placeholder="profile?.hasSkePassword ? $t('profiles.savedKeep') : $t('profiles.skePlaceholder')"
      />
    </div>

    <div class="field">
      <label>{{ $t('profiles.basePathField') }}</label>
      <input v-model="form.basePath" placeholder="/encrypted" />
    </div>

    <label class="dlg-check">
      <input v-model="form.remember" type="checkbox" />
      <span>{{ $t('profiles.remember') }}</span>
    </label>

    <template #footer>
      <button type="button" class="ghost" :disabled="busy" @click="close">
        {{ $t('common.cancel') }}
      </button>
      <button type="submit" class="primary" :disabled="busy">
        {{ busy ? $t('profiles.saving') : $t('profiles.save') }}
      </button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'
import { saveProfile } from '../state/session.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  profile: { type: Object, default: null },
})

const emit = defineEmits(['close', 'saved'])

const busy = ref(false)
const error = ref('')

const form = reactive({
  name: '',
  provider: 'alist',
  server: '',
  username: '',
  password: '',
  skePassword: '',
  basePath: '',
  remember: true,
})

const providerLabel = computed(() => (form.provider === 'openlist' ? 'OpenList' : 'AList'))
const serverPlaceholder = computed(() =>
  form.provider === 'openlist' ? 'https://openlist.example.com' : 'https://alist.example.com',
)

watch(
  () => [props.visible, props.profile],
  () => {
    if (!props.visible) return
    error.value = ''
    form.name = props.profile?.name || ''
    form.provider = props.profile?.provider === 'openlist' ? 'openlist' : 'alist'
    form.server = props.profile?.server || ''
    form.username = props.profile?.username || ''
    form.basePath = props.profile?.basePath || ''
    form.remember = props.profile?.remember ?? true
    // Never pre-fill secrets: the UI has no way to show them back, and an empty
    // field already means "keep the stored value".
    form.password = ''
    form.skePassword = ''
  },
  { immediate: true },
)

function close() {
  if (busy.value) return
  emit('close')
}

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await saveProfile({
      id: props.profile?.id,
      name: form.name,
      provider: form.provider,
      server: form.server,
      username: form.username,
      password: form.password,
      skePassword: form.skePassword,
      basePath: form.basePath,
      remember: form.remember,
    })
    emit('saved')
  } catch (err) {
    error.value = err.message
  } finally {
    busy.value = false
  }
}
</script>
