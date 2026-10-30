<template>
  <div class="lock">
    <form class="panel" @submit.prevent="submit">
      <div class="mark">
        <span class="dot" />
      </div>
      <h1>Sakura Encryptor</h1>
      <p class="hint">{{ hint }}</p>

      <input
        v-if="needsPassword"
        ref="passwordInput"
        v-model="password"
        type="password"
        placeholder="启动密码"
        autocomplete="off"
        :disabled="busy || remain > 0"
        @keydown.enter="focusCode"
      />

      <input
        v-if="needsTotp"
        ref="totpInput"
        v-model="code"
        class="code"
        type="text"
        inputmode="numeric"
        autocomplete="one-time-code"
        maxlength="6"
        placeholder="6 位验证码"
        :disabled="busy || remain > 0"
        @input="onCodeInput"
      />

      <p v-if="error" class="error">{{ error }}</p>
      <p v-else-if="remain > 0" class="error">尝试过多，{{ remain }} 秒后可重试</p>

      <button class="primary" type="submit" :disabled="!canSubmit || busy || remain > 0">
        {{ busy ? '验证中…' : '解锁' }}
      </button>
    </form>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { session, unlock } from '../state/session.js'

const password = ref('')
const code = ref('')
const error = ref('')
const busy = ref(false)
const passwordInput = ref(null)
const totpInput = ref(null)
const now = ref(Date.now())
let timer = null

/**
 * Which fields to show.
 *
 * The main process decides what the gate demands (`factors`) and how the
 * proofs combine (`match`); this component only lays them out. An empty list
 * cannot happen while the gate is up, but falling back to a password field
 * keeps a broken state from rendering a form with no way in.
 */
const factors = computed(() => (session.lock.factors?.length ? session.lock.factors : ['password']))
const needsPassword = computed(() => factors.value.includes('password'))
const needsTotp = computed(() => factors.value.includes('totp'))
const anyOf = computed(() => factors.value.length > 1 && session.lock.match === 'any')

const hint = computed(() => {
  if (anyOf.value) return '启动密码或验证码，任一通过即可'
  if (needsPassword.value && needsTotp.value) return '需要启动密码和验证码都通过'
  if (needsTotp.value) return '输入令牌应用中的 6 位验证码'
  return '输入启动密码以解锁'
})

/** In "any" mode one filled field is enough; otherwise every shown field is required. */
const canSubmit = computed(() => {
  const hasPassword = !!password.value
  const hasCode = code.value.length === 6
  if (anyOf.value) return hasPassword || hasCode
  return (!needsPassword.value || hasPassword) && (!needsTotp.value || hasCode)
})

const remain = computed(() => {
  const until = session.lock.lockedUntil || 0
  if (until <= now.value) return 0
  return Math.ceil((until - now.value) / 1000)
})

onMounted(async () => {
  await nextTick()
  if (needsPassword.value) passwordInput.value?.focus()
  else totpInput.value?.focus()

  timer = setInterval(() => {
    now.value = Date.now()
  }, 500)
})

onUnmounted(() => clearInterval(timer))

/** Enter on the password moves on instead of submitting a half-filled form. */
function focusCode(event) {
  if (!needsTotp.value || code.value) return
  event.preventDefault()
  totpInput.value?.focus()
}

function onCodeInput(event) {
  const digits = String(event.target.value || '').replace(/\D/g, '').slice(0, 6)
  code.value = digits
  event.target.value = digits
}

async function submit() {
  if (!canSubmit.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await unlock({
      password: needsPassword.value ? password.value : '',
      totp: needsTotp.value ? code.value : '',
    })
    password.value = ''
    code.value = ''
  } catch (err) {
    error.value = err.message
    password.value = ''
    code.value = ''
    if (needsPassword.value) passwordInput.value?.focus()
    else totpInput.value?.focus()
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.lock {
  position: fixed;
  /* Below the title bar, and below it in the stack: 40 px is the caption strip's
     height. Covering it turned the whole frameless window into one no-drag box,
     so a locked window could neither be moved nor closed without unlocking. */
  inset: 40px 0 0;
  z-index: 90;
  display: grid;
  place-items: center;
  background: radial-gradient(1200px 600px at 50% -10%, var(--panel-hover) 0%, var(--bg) 60%);
}

.panel {
  width: 320px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 30px 28px 26px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
}

.mark {
  display: grid;
  place-items: center;
  margin-bottom: 2px;
}

.dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 22px var(--accent);
}

h1 {
  font-size: 16px;
  text-align: center;
  letter-spacing: 0.02em;
}

.hint {
  margin: -4px 0 6px;
  text-align: center;
  font-size: 12px;
  color: var(--muted);
}

/* A one-time code is read digit by digit, so widen the tracking. */
.code {
  font-family: var(--mono, ui-monospace, monospace);
  letter-spacing: 0.35em;
  text-align: center;
  font-size: 16px;
}

.error {
  margin: -2px 0 0;
  font-size: 12px;
  color: var(--danger);
}

button {
  margin-top: 4px;
  padding: 9px 14px;
}
</style>

