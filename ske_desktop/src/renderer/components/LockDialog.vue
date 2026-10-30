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
        placeholder="当前密码"
      />
      <input
        ref="passwordField"
        v-model="password"
        type="password"
        autocomplete="new-password"
        :placeholder="`新密码，至少 ${minPassword} 位`"
      />
    </template>

    <template v-else-if="mode === 'totp'">
      <p class="dlg-desc">
        用验证器应用扫描或导入下面的 otpauth 链接，再输入它显示的 6 位验证码。
      </p>

      <!-- The default path is "take this new key". Reusing a token the user
           already registered is the exception, so it lives behind a link
           instead of sitting in the middle of the form looking mandatory. -->
      <template v-if="!useExisting">
        <div class="mono secret">{{ secret || '正在生成密钥…' }}</div>
        <div v-if="uri" class="mono uri" :title="uri">{{ uri }}</div>
      </template>

      <template v-else>
        <input
          ref="pastedField"
          v-model="pasted"
          class="mono"
          autocomplete="off"
          placeholder="粘贴 Base32 密钥或 otpauth 链接"
        />
        <p class="hint">
          使用验证器里已有的那个密钥，绑定后两边的验证码会一致。
        </p>
      </template>

      <input
        ref="first"
        v-model="code"
        class="code"
        inputmode="numeric"
        maxlength="6"
        placeholder="6 位验证码"
        @input="onCodeInput"
      />

      <div class="totp-foot">
        <button
          v-if="!useExisting"
          class="ghost sm"
          type="button"
          @click="emit('regenerate')"
        >
          换一个密钥
        </button>
        <span />
        <button class="link" type="button" @click="toggleExisting">
          {{ useExisting ? '改用新生成的密钥' : '已经在验证器里？' }}
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
        placeholder="当前密码"
      />
      <input
        v-if="hasTotp"
        v-model="code"
        class="code"
        inputmode="numeric"
        maxlength="6"
        placeholder="6 位验证码"
        @input="onCodeInput"
      />
    </template>

    <template #footer>
      <button class="ghost" type="button" :disabled="busy" @click="cancel">取消</button>
      <button
        :class="mode === 'disable' ? 'danger' : 'primary'"
        type="submit"
        :disabled="busy || !canSubmit"
      >
        {{ busy ? '处理中…' : confirmText }}
      </button>
    </template>
  </AppDialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import AppDialog from './AppDialog.vue'

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
  if (props.mode === 'totp') return '绑定 TOTP 身份验证器'
  if (props.mode === 'disable') return '关闭启动锁'
  return props.hasPassword ? '修改启动密码' : '设置启动密码'
})

const description = computed(() => {
  if (props.mode === 'disable') {
    const factors = [props.hasPassword ? '密码' : '', props.hasTotp ? 'TOTP' : '']
      .filter(Boolean)
      .join('与')
    return `${factors} 会一并清除，需要先验证当前已启用的方式。`
  }
  if (props.mode === 'password' && props.hasPassword) {
    return '修改后下次启动生效，当前会话保持解锁。'
  }
  return ''
})

const confirmText = computed(() => {
  if (props.mode === 'totp') return '确认绑定'
  if (props.mode === 'disable') return '关闭启动锁'
  return props.hasPassword ? '修改' : '启用'
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
