<template>
  <div class="login-page">
    <div class="login-wrapper">
      <!-- Decorative background orbs -->
      <div class="orb orb-1"></div>
      <div class="orb orb-2"></div>

      <div class="login-card glass-card">
        <div class="login-header">
          <div class="logo">
            <img src="/logo.png" alt="Sakura Encryptor Logo" width="64" height="64" />
          </div>
          <h1>Sakura Encryptor</h1>
        </div>

        <form @submit.prevent="handleLogin" class="login-form">
          <!-- AList Config -->
          <div class="section-label">AList 连接</div>

          <div class="form-group">
            <label for="alist-server">服务器地址</label>
            <input id="alist-server" class="input-field" type="url" v-model="alistServer"
              placeholder="https://alist.example.com" required />
          </div>

          <div class="form-row">
            <div class="form-group">
              <label for="alist-user">用户名</label>
              <input id="alist-user" class="input-field" type="text" v-model="alistUser" placeholder="admin" />
            </div>
            <div class="form-group">
              <label for="alist-pass">密码</label>
              <input id="alist-pass" class="input-field" type="password" v-model="alistPass" placeholder="••••••" />
            </div>
          </div>

          <div class="form-group">
            <label for="alist-root">AList 根目录 (可选，例如: /移动家庭云)</label>
            <input id="alist-root" class="input-field" type="text" v-model="alistRootPath" placeholder="/" />
          </div>

          <div class="divider"></div>

          <!-- Master Password -->
          <div class="section-label">加密主密码</div>

          <div class="form-group">
            <label for="master-pw">主密码（用于加密视频，仅观看原始流媒体可留空）</label>
            <input id="master-pw" class="input-field" type="password" v-model="masterPassword"
              placeholder="输入加密时使用的密码" />
          </div>

          <div v-if="error" class="error-message">{{ error }}</div>

          <button type="submit" class="btn btn-primary btn-full" :disabled="loading">
            <span v-if="loading" class="spinner"></span>
            <span v-else>🔓 解锁并进入</span>
          </button>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { storeAndPostKey } from '../composables/useCrypto.js'
import { login as alistLogin } from '../composables/useAList.js'

const router = useRouter()

const alistServer = ref('')
const alistUser = ref('')
const alistPass = ref('')
const alistRootPath = ref('')
const masterPassword = ref('')
const loading = ref(false)
const error = ref('')

// Automatically restore saved AList configuration on start
onMounted(() => {
  const savedServer = localStorage.getItem('ske_alist_server')
  const savedUser = localStorage.getItem('ske_alist_user')
  const savedPass = localStorage.getItem('ske_alist_pass')
  const savedRoot = localStorage.getItem('ske_alist_root')
  if (savedServer) alistServer.value = savedServer
  if (savedUser) alistUser.value = savedUser
  if (savedPass) alistPass.value = savedPass
  if (savedRoot) alistRootPath.value = savedRoot
})

async function handleLogin() {
  error.value = ''
  loading.value = true

  try {
    // 1) Login to AList
    await alistLogin(alistServer.value, alistUser.value, alistPass.value, alistRootPath.value)

    // Save configuration persistently
    localStorage.setItem('ske_alist_server', alistServer.value)
    localStorage.setItem('ske_alist_user', alistUser.value)
    localStorage.setItem('ske_alist_pass', alistPass.value)
    localStorage.setItem('ske_alist_root', alistRootPath.value)

    // 2) Derive key & send to SW (only if password provided)
    if (masterPassword.value) {
      await storeAndPostKey(masterPassword.value)
    }

    // 3) Navigate to file browser
    router.push('/browse/')
  } catch (err) {
    error.value = err.message || '连接失败，请检查配置'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  position: relative;
  overflow: hidden;
}

.login-wrapper {
  position: relative;
  width: 100%;
  max-width: 440px;
  z-index: 1;
}

/* Decorative orbs */
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.5;
  pointer-events: none;
  animation: float 8s ease-in-out infinite;
}
.orb-1 {
  width: 300px;
  height: 300px;
  background: var(--accent-start);
  top: -100px;
  left: -80px;
  opacity: 0.15;
}
.orb-2 {
  width: 250px;
  height: 250px;
  background: var(--accent-end);
  bottom: -80px;
  right: -60px;
  opacity: 0.12;
  animation-delay: -4s;
}
@keyframes float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-20px); }
}

.login-card {
  padding: 40px 36px;
  position: relative;
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.logo {
  display: inline-block;
  margin-bottom: 16px;
  animation: pulse-glow 3s ease-in-out infinite;
}
@keyframes pulse-glow {
  0%, 100% { filter: drop-shadow(0 0 8px rgba(139, 92, 246, 0.3)); }
  50% { filter: drop-shadow(0 0 20px rgba(139, 92, 246, 0.5)); }
}

.login-header h1 {
  font-size: 28px;
  font-weight: 700;
  background: var(--accent-gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.subtitle {
  color: var(--text-secondary);
  font-size: 14px;
  margin-top: 4px;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--text-muted);
  margin-top: 4px;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.divider {
  height: 1px;
  background: var(--border-glass);
  margin: 4px 0;
}

.error-message {
  font-size: 13px;
  color: var(--danger);
  padding: 8px 12px;
  background: rgba(239, 68, 68, 0.1);
  border-radius: var(--radius-sm);
  border: 1px solid rgba(239, 68, 68, 0.2);
}

.btn-full {
  width: 100%;
  padding: 14px;
  font-size: 15px;
  margin-top: 8px;
}
</style>
