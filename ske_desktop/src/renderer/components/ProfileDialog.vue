<template>
  <AppDialog
    :visible="visible"
    :title="profile ? '编辑配置' : '新建配置'"
    description="每个配置是一套 AList / OpenList 账号加一套独立的加密密码。"
    :width="460"
    :busy="busy"
    :error="error"
    @cancel="close"
    @submit="submit"
  >
    <div class="field">
      <label>配置名称</label>
      <input v-model="form.name" placeholder="例如 家里 NAS" autofocus />
    </div>

    <div class="field">
      <label>服务端类型</label>
      <select v-model="form.provider">
        <option value="alist">AList</option>
        <option value="openlist">OpenList</option>
      </select>
      <!-- 2FA codes are asked for on every login, because a one-time code
           cannot be stored. Both backends support it. -->
      <span class="meta">
        账号若开启了两步验证，连接时会提示输入动态验证码。
      </span>
    </div>

    <div class="field">
      <label>服务器地址</label>
      <input v-model="form.server" :placeholder="serverPlaceholder" />
    </div>

    <div class="field">
      <label>用户名</label>
      <input v-model="form.username" :placeholder="`${providerLabel} 用户名`" autocomplete="off" />
    </div>

    <div class="field">
      <label>{{ providerLabel }} 密码</label>
      <input
        v-model="form.password"
        type="password"
        autocomplete="new-password"
        :placeholder="profile?.hasPassword ? '已保存，留空则不修改' : `${providerLabel} 登录密码`"
      />
      <span class="meta">使用系统钥匙串加密保存，不会明文落盘。</span>
    </div>

    <div class="field">
      <label>媒体加密密码</label>
      <input
        v-model="form.skePassword"
        type="password"
        autocomplete="new-password"
        :placeholder="profile?.hasSkePassword ? '已保存，留空则不修改' : '解密 .ske 文件用的密码'"
      />
    </div>

    <div class="field">
      <label>根目录（可选）</label>
      <input v-model="form.basePath" placeholder="/encrypted" />
    </div>

    <label class="dlg-check">
      <input v-model="form.remember" type="checkbox" />
      <span>记住密码（关闭后每次连接都需要重新输入）</span>
    </label>

    <template #footer>
      <button type="button" class="ghost" :disabled="busy" @click="close">取消</button>
      <button type="submit" class="primary" :disabled="busy">
        {{ busy ? '保存中…' : '保存' }}
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
