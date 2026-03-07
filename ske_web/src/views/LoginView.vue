<template>
  <div class="login-page">
    <div class="login-wrapper">
      <!-- Decorative background orbs -->
      <div class="orb orb-1"></div>
      <div class="orb orb-2"></div>

      <div class="login-header">
        <h1>Sakura Encryptor</h1>
        <p class="subtitle">极简、美观、安全的高性能流式解密播放器</p>
      </div>

      <div class="login-card glass-card">

        <!-- Tab Switcher -->
        <div class="tab-switcher">
          <button class="tab-btn" :class="{ active: currentTab === 'alist' }" @click="currentTab = 'alist'">
            AList 云端
          </button>
          <button class="tab-btn" :class="{ active: currentTab === 'local' }" @click="currentTab = 'local'">
            本地库
          </button>
        </div>

        <form @submit.prevent="handleUnifiedSubmit" class="login-form">
          <!-- AList Tab Content -->
          <div v-if="currentTab === 'alist'" class="tab-content transition-fade">
            <div class="section-label">AList 连接配置</div>
            <div class="form-group">
              <label for="alist-server">服务器地址</label>
              <input id="alist-server" class="input-field" type="text" v-model="alistServer"
                placeholder="https://alist.example.com" :required="currentTab === 'alist'" />
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
              <label for="alist-root">AList 根目录 (可选)</label>
              <input id="alist-root" class="input-field" type="text" v-model="alistRootPath" placeholder="/" />
            </div>
          </div>

          <!-- Local Tab Content -->
          <div v-else class="tab-content transition-fade">
            <div class="section-label">本地文件/文件夹</div>
            <div class="local-picker-zone" @click="triggerFolderPicker">
              <div class="picker-icon">📂</div>
              <div class="picker-text">
                <span v-if="localFileCount === 0">点击或并选择文件夹</span>
                <span v-else>已选择 {{ localFileCount }} 个文件</span>
              </div>
              <input type="file" ref="folderInput" class="hidden-input" webkitdirectory directory multiple @change="onLocalFilesChange" />
            </div>
            <p class="hint-text">通过浏览器的 File System API 安全读取，完全离线运行</p>
          </div>

          <div class="divider"></div>

          <!-- Shared Master Password -->
          <div class="section-label">加密主密码</div>
          <div class="form-group">
            <label for="master-pw">主密码（解密 .ske 文件必备）</label>
            <input id="master-pw" class="input-field" type="password" v-model="masterPassword"
              placeholder="输入加密时使用的密码" />
          </div>

          <div v-if="error" class="error-message">{{ error }}</div>

          <button type="submit" class="btn btn-primary btn-full" :disabled="loading">
            <span v-if="loading" class="spinner"></span>
            <span v-else>{{ currentTab === 'alist' ? '🔓 登录' : '🚀 浏览本地库' }}</span>
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
import { useLocalFiles } from '../composables/useLocalFiles.js'

const router = useRouter()
const { registerFiles, clearFiles } = useLocalFiles()

const currentTab = ref('alist')
const folderInput = ref(null)
const localFileCount = ref(0)
const selectedFiles = ref([])

const alistServer = ref('')
const alistUser = ref('')
const alistPass = ref('')
const alistRootPath = ref('')
const masterPassword = ref('')
const loading = ref(false)
const error = ref('')

onMounted(() => {
  const savedServer = localStorage.getItem('ske_alist_server')
  if (savedServer) alistServer.value = savedServer

  // 🧹 Cleanup: stop remembering sensitive info as requested
  localStorage.removeItem('ske_alist_user')
  localStorage.removeItem('ske_alist_pass')
  localStorage.removeItem('ske_alist_root')
})

function triggerFolderPicker() {
  folderInput.value.click()
}

function onLocalFilesChange(e) {
  const files = e.target.files
  if (files && files.length > 0) {
    selectedFiles.value = files
    localFileCount.value = files.length
  }
}

async function handleUnifiedSubmit() {
  error.value = ''
  loading.value = true

  try {
    // 1) Set Master Password first
    if (masterPassword.value) {
      await storeAndPostKey(masterPassword.value)
    }

    if (currentTab.value === 'alist') {
      // 2a) AList Flow
      await alistLogin(alistServer.value, alistUser.value, alistPass.value, alistRootPath.value)
      
      localStorage.setItem('ske_alist_server', alistServer.value)

      router.push('/browse/')
    } else {
      // 2b) Local Flow
      if (selectedFiles.value.length === 0) {
        throw new Error('请先选择一个文件夹或文件')
      }
      await registerFiles(selectedFiles.value)
      router.push('/local/')
    }
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

/* Tab Switcher */
.tab-switcher {
  display: flex;
  background: rgba(255, 255, 255, 0.05);
  padding: 4px;
  border-radius: var(--radius-lg);
  margin-bottom: 24px;
}

.tab-btn {
  flex: 1;
  padding: 10px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  border-radius: var(--radius-md);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.tab-btn.active {
  background: rgba(255, 255, 255, 0.1);
  color: var(--text-primary);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.tab-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* Local Picker Styling */
.local-picker-zone {
  background: rgba(255, 255, 255, 0.03);
  border: 2px dashed rgba(255, 255, 255, 0.1);
  border-radius: var(--radius-lg);
  padding: 32px 24px;
  text-align: center;
  cursor: pointer;
  transition: all 0.3s ease;
}

.local-picker-zone:hover {
  background: rgba(255, 255, 255, 0.05);
  border-color: var(--accent-start);
}

.picker-icon {
  font-size: 32px;
  margin-bottom: 12px;
}

.picker-text {
  font-size: 14px;
  color: var(--text-secondary);
}

.hidden-input {
  display: none;
}

.hint-text {
  font-size: 11px;
  color: var(--text-muted);
  text-align: center;
  margin-top: -8px;
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
  margin-bottom: 50px;
}

.login-header h1 {
  font-size: 39px;
  font-weight: 800;
  background: var(--accent-gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  line-height: 1.2;
  letter-spacing: -0.02em;
}

.subtitle {
  color: var(--text-muted);
  font-size: 14px;
  margin-top: 10px;
  letter-spacing: 0.05em;
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
  background: rgba(255, 255, 255, 0.1);
  margin: 4px 0;
}

.error-message {
  font-size: 13px;
  color: var(--danger);
  padding: 10px 14px;
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

.transition-fade {
  animation: fadeIn 0.4s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(5px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
