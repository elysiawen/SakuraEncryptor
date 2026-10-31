<!--
  The local server's own admin account.
  Separate from the media password: this one only guards the loopback API.
-->
<template>
  <AppDialog
    :visible="visible"
    title="内置服务器 · 账号"
    description="这是内置服务器自己的管理员账号，只在应用内部使用，和媒体加密密码无关。"
    :width="460"
    :busy="busy"
    @cancel="close"
  >
    <p v-if="state?.status !== 'running'" class="hint">
      服务器没有在运行，先在设置里开启，再回来修改账号。
    </p>

    <template v-else>
      <div class="field">
        <label for="embedded-user">用户名</label>
        <input id="embedded-user" v-model="draft.username" type="text" placeholder="admin" />
        <div class="meta">当前：{{ embedded.account.username || 'admin' }}</div>
      </div>

      <div class="field">
        <label for="embedded-pass">密码</label>
        <input id="embedded-pass" v-model="draft.password" type="text" placeholder="留空表示不修改" />
        <div class="row inline">
          <button class="ghost sm" type="button" @click="reveal">显示当前</button>
          <button class="ghost sm" type="button" @click="copy">复制</button>
          <button class="ghost sm" type="button" @click="roll">随机生成</button>
        </div>
        <div class="meta">
          {{ revealed ? `当前密码：${revealed}` : '密码由应用生成，用系统钥匙串加密保存' }}
        </div>
      </div>
    </template>

    <template #footer>
      <button class="ghost" type="button" @click="close">关闭</button>
      <button class="primary" type="button" :disabled="busy || !changed" @click="save">保存</button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'
import AppDialog from './AppDialog.vue'
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
    toast('密码已复制', 'ok')
  } catch {
    toast('复制失败，请手动选中', 'error')
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
