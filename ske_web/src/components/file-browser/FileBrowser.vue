<template>
  <div class="file-browser" @contextmenu.capture.prevent="handleBrowserContextMenu">
    <input
      v-if="props.mode === 'alist'"
      ref="uploadInput"
      type="file"
      multiple
      class="hidden-file-input"
      @change="handleUploadSelect"
    />

    <FileGrid
      :items="items"
      :loading="loading"
      :error="error"
      @click="handleClick"
      @retry="loadCurrentPath"
      @contextmenu="openMenu"
    />

    <button
      v-if="props.mode === 'alist'"
      class="upload-fab"
      :style="uploadFabBottom"
      :disabled="upload.loading"
      @click="openUploadPicker"
      title="上传文件"
    >
      📤
    </button>

    <button
      class="refresh-fab"
      :class="{ spinning: loading }"
      @click="() => loadCurrentPath(false, true)"
      title="刷新"
    >
      🔄
    </button>

    <!-- Context Menu -->
    <Teleport to="body">
      <Transition name="ctx-pop">
        <div
          v-if="menu.show"
          class="ctx-menu glass-card"
          :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
          @click.stop
        >
          <div v-if="!menu.item" class="ctx-item" @click="startNewFolder">
            <span>📁</span> 新建文件夹
          </div>
          <div
            v-if="menu.item && !menu.item.is_dir && props.mode === 'alist'"
            class="ctx-item"
            @click="startDownload"
          >
            <span>⬇️</span> 下载
          </div>
          <div v-if="menu.item" class="ctx-item" @click="startRename">
            <span>✏️</span> 重命名
          </div>
          <div v-if="menu.item" class="ctx-item ctx-danger" @click="confirmDelete">
            <span>🗑️</span> 删除
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- New Folder Dialog -->
    <n-modal v-model:show="mkdir.show" preset="card" title="新建文件夹" class="ske-modal" style="max-width: 380px;">
      <n-input
        v-model:value="mkdir.name"
        placeholder="文件夹名称"
        @keyup.enter="doMkdir"
        :input-props="{ autocomplete: 'off' }"
      />
      <div class="modal-option">
        <n-checkbox v-model:checked="mkdir.encrypt">
          加密
        </n-checkbox>
      </div>
      <template #action>
        <div class="modal-actions">
          <n-button class="modal-btn" quaternary @click="mkdir.show = false">取消</n-button>
          <n-button class="modal-btn" type="primary" :loading="mkdir.loading" @click="doMkdir">创建</n-button>
        </div>
      </template>
    </n-modal>

    <!-- Upload Dialog -->
    <n-modal v-model:show="upload.show" preset="card" title="上传文件" class="ske-modal" style="max-width: 440px;">
      <p class="modal-desc">已选择 {{ upload.files.length }} 个文件</p>
      <div v-if="upload.files.length" class="upload-file-list">
        <div
          v-for="file in upload.files"
          :key="file.name + '-' + file.lastModified + '-' + file.size"
          class="upload-file-item"
        >
          <span class="upload-file-name">{{ file.name }}</span>
          <span class="upload-file-size">{{ formatFileSize(file.size) }}</span>
        </div>
      </div>
      <div class="modal-option">
        <n-checkbox v-model:checked="upload.encrypt">
          加密上传
        </n-checkbox>
      </div>
      <p v-if="upload.progressText" class="upload-progress">{{ upload.progressText }}</p>
      <template #action>
        <div class="modal-actions">
          <n-button class="modal-btn" quaternary :disabled="upload.loading" @click="resetUpload">取消</n-button>
          <n-button class="modal-btn" type="primary" :loading="upload.loading" @click="doUpload">上传</n-button>
        </div>
      </template>
    </n-modal>

    <!-- Rename Dialog -->
    <n-modal v-model:show="rename.show" preset="card" title="重命名" class="ske-modal" style="max-width: 380px;">
      <n-input
        v-model:value="rename.newName"
        placeholder="输入新名称"
        @keyup.enter="doRename"
        :input-props="{ autocomplete: 'off' }"
      />
      <template #action>
        <div class="modal-actions">
          <n-button class="modal-btn" quaternary @click="rename.show = false">取消</n-button>
          <n-button class="modal-btn" type="primary" :loading="rename.loading" @click="doRename">确定</n-button>
        </div>
      </template>
    </n-modal>

    <!-- Delete Confirm -->
    <n-modal v-model:show="del.show" preset="card" title="确认删除" class="ske-modal" style="max-width: 380px;">
      <p class="modal-desc">确定要删除「{{ del.item?.decName }}」吗？此操作不可撤销。</p>
      <template #action>
        <div class="modal-actions">
          <n-button class="modal-btn" quaternary @click="del.show = false">取消</n-button>
          <n-button class="modal-btn" type="error" :loading="del.loading" @click="doDelete">删除</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FileGrid from './FileGrid.vue'
import { getNameKeyFromSession, getPasswordFromSession, decryptName, encryptName, encryptFileContent } from '../../composables/useCrypto.js'
import { globalState } from '../../composables/useGlobalState.js'
import { getFileType } from '../../composables/useFileDetection.js'
import { renameItem, deleteItems, makeDir, getFileInfo, uploadFile as uploadAListFile } from '../../composables/useAList.js'
import { useFabManager, FAB_BASE_DESKTOP, FAB_BASE_MOBILE, FAB_SIZE, FAB_GAP } from '../../composables/useFabManager.js'
import { useDownloadManager } from '../../composables/useDownloadManager.js'
import { buildDecryptProxyUrl, isEncryptedFileName, toDownloadUrl } from '../../composables/useFileDownload.js'

const props = defineProps({
  mode: { type: String, required: true },
  listFn: { type: Function },
  localState: { type: Object },
})

const route = useRoute()
const router = useRouter()
const { register: registerFab, unregister: unregisterFab, getOrder } = useFabManager()
const { addTask: addDownloadTask } = useDownloadManager()

const items = ref([])
const loading = ref(false)
const error = ref('')
const currentPath = ref('')
const uploadInput = ref(null)
const uploadFabBottom = computed(() => {
  const order = getOrder('upload')
  return {
    '--fab-bottom': `${FAB_BASE_DESKTOP + order * (FAB_SIZE + FAB_GAP)}px`,
    '--fab-bottom-mobile': `${FAB_BASE_MOBILE + order * (FAB_SIZE + FAB_GAP)}px`,
  }
})

// ── Context Menu ──
const menu = reactive({ show: false, x: 0, y: 0, item: null })

function openMenu(e, item) {
  const pad = 8
  const menuWidth = 160
  const menuHeight = 180
  let x = e.clientX
  let y = e.clientY
  if (x + menuWidth > window.innerWidth) x = window.innerWidth - menuWidth - pad
  if (y + menuHeight > window.innerHeight) y = window.innerHeight - menuHeight - pad
  menu.x = x
  menu.y = y
  menu.item = item
  menu.show = true
}

function openBlankMenu(e) {
  if (e.target.closest('.file-card') || e.target.closest('.ctx-menu')) return
  openMenu(e, null)
}

function handleBrowserContextMenu(e) {
  if (e.target.closest('.file-card')) return
  openBlankMenu(e)
}

function closeMenu() {
  menu.show = false
}

function onDocClick() {
  closeMenu()
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  registerFab('refresh', 0)
  if (props.mode === 'alist') registerFab('upload', 1)
})
onUnmounted(() => {
  document.removeEventListener('click', onDocClick)
  unregisterFab('upload')
  unregisterFab('refresh')
})

// ── New Folder ──
const mkdir = reactive({ show: false, name: '', encrypt: true, loading: false })

function startNewFolder() {
  mkdir.name = ''
  mkdir.encrypt = true
  mkdir.show = true
  closeMenu()
}

async function doMkdir() {
  const folderName = mkdir.name.trim()
  if (!folderName) return
  mkdir.loading = true
  try {
    const nameKey = await getNameKeyFromSession()
    const path = getRoutePath()

    if (props.mode === 'alist') {
      const targetName = (mkdir.encrypt && nameKey)
        ? await encryptName(folderName, nameKey)
        : folderName
      await makeDir(joinPath(path, targetName))
    }

    mkdir.show = false
    await loadCurrentPath(false, true)
  } catch (err) {
    error.value = err.message || '创建文件夹失败'
  } finally {
    mkdir.loading = false
  }
}

// ── Upload ──
const upload = reactive({ show: false, files: [], encrypt: true, loading: false, progressText: '' })

function openUploadPicker() {
  if (upload.loading) return
  uploadInput.value?.click()
}

function handleUploadSelect(event) {
  const selected = Array.from(event.target.files || [])
  if (!selected.length) return
  upload.files = selected
  upload.encrypt = true
  upload.progressText = `已选择 ${selected.length} 个文件`
  upload.show = true
  event.target.value = ''
}

function resetUpload(force = false) {
  if (upload.loading && !force) return
  upload.show = false
  upload.files = []
  upload.encrypt = true
  upload.progressText = ''
}

async function doUpload() {
  if (!upload.files.length) return
  upload.loading = true
  error.value = ''

  try {
    const path = getRoutePath()
    const nameKey = await getNameKeyFromSession()
    const password = getPasswordFromSession()

    if (upload.encrypt && (!nameKey || !password)) {
      throw new Error('缺少加密主密码，无法加密上传')
    }

    for (let i = 0; i < upload.files.length; i++) {
      const sourceFile = upload.files[i]
      upload.progressText = `正在上传 ${i + 1}/${upload.files.length}: ${sourceFile.name}`

      let targetName = sourceFile.name
      let fileToUpload = sourceFile

      if (upload.encrypt) {
        targetName = `${await encryptName(sourceFile.name, nameKey)}.ske`
        const encryptedBlob = await encryptFileContent(sourceFile, password)
        fileToUpload = new File([encryptedBlob], targetName, {
          type: 'application/octet-stream',
          lastModified: sourceFile.lastModified,
        })
      }

      await uploadAListFile(joinPath(path, targetName), fileToUpload)
    }

    resetUpload(true)
    await loadCurrentPath(false, true)
  } catch (err) {
    error.value = err.message || '上传失败'
  } finally {
    upload.loading = false
  }
}

// ── Rename ──
const rename = reactive({ show: false, newName: '', loading: false })

function startRename() {
  rename.newName = menu.item?.decName || ''
  rename.show = true
  closeMenu()
}

async function doRename() {
  if (!rename.newName.trim() || !menu.item) return
  rename.loading = true
  try {
    const nameKey = await getNameKeyFromSession()
    const path = getRoutePath()

    if (props.mode === 'alist' && nameKey) {
      const encNew = await encryptName(rename.newName.trim(), nameKey)
      const hasSke = menu.item.encName.endsWith('.ske')
      const finalName = hasSke ? encNew + '.ske' : encNew
      await renameItem(path + '/' + menu.item.encName, finalName)
    }

    rename.show = false
    await loadCurrentPath(false, true)
  } catch (err) {
    error.value = err.message || '重命名失败'
  } finally {
    rename.loading = false
  }
}

// ── Delete ──
const del = reactive({ show: false, item: null, loading: false })

function confirmDelete() {
  del.item = menu.item
  del.show = true
  closeMenu()
}

async function doDelete() {
  if (!del.item) return
  del.loading = true
  try {
    const path = getRoutePath()
    if (props.mode === 'alist') {
      await deleteItems(path, [del.item.encName])
    }
    del.show = false
    await loadCurrentPath(false, true)
  } catch (err) {
    error.value = err.message || '删除失败'
  } finally {
    del.loading = false
  }
}

// ── Download ──
async function startDownload() {
  const item = menu.item
  if (!item || item.is_dir) return
  closeMenu()

  try {
    const absPath = joinPath(getRoutePath(), item.encName)
    const info = await getFileInfo(absPath)
    if (!info.url) throw new Error('无法获取下载链接')

    if (isEncryptedFileName(item.encName)) {
      // Decrypt through the Service Worker so the user gets the plaintext file.
      addDownloadTask({
        url: toDownloadUrl(buildDecryptProxyUrl(info.url, info.size)),
        name: item.decName,
        total: 0,
      })
      return
    }

    addDownloadTask({
      url: info.url,
      name: item.decName,
      total: info.size || item.size || 0,
    })
  } catch (err) {
    error.value = err.message || '下载失败'
  }
}

// ── Route helpers ──
function getRoutePath() {
  if (props.mode === 'local') return currentPath.value || ''
  const p = route.params.path
  if (!p) return '/'
  const joined = Array.isArray(p) ? p.join('/') : p
  return '/' + joined
}

function joinPath(base, name) {
  return base === '/' ? '/' + name : base + '/' + name
}

function formatFileSize(size) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`
}

async function loadCurrentPath(_, refresh = false) {
  loading.value = true
  error.value = ''
  items.value = []

  try {
    const nameKey = await getNameKeyFromSession()
    const path = getRoutePath()

    let content = []

    if (props.mode === 'alist') {
      const data = await props.listFn(path, refresh)
      content = (data.content || []).map((entry) => ({
        encName: entry.name,
        decName: entry.name,
        is_dir: entry.is_dir,
        size: entry.size,
        modified: entry.modified,
      }))
    } else {
      content = scanLocalFiles(path)
    }

    if (nameKey) {
      await Promise.all(
        content.map(async (item) => {
          let nameToDecrypt = item.encName
          if (nameToDecrypt.endsWith('.ske')) nameToDecrypt = nameToDecrypt.slice(0, -4)
          const result = await decryptName(nameToDecrypt, nameKey)
          if (result) item.decName = result
        })
      )
    }

    content.sort((a, b) => {
      if (a.is_dir !== b.is_dir) return a.is_dir ? -1 : 1
      return a.decName.localeCompare(b.decName, 'zh-CN')
    })

    items.value = content

    const pathParts = path.split('/').filter(Boolean)
    if (nameKey && pathParts.length > 0) {
      const decSeg = await Promise.all(
        pathParts.map(async (seg) => {
          const result = await decryptName(seg, nameKey)
          return result || seg
        })
      )
      globalState.decryptedSegments = decSeg
    } else {
      globalState.decryptedSegments = pathParts
    }
  } catch (err) {
    error.value = err.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function scanLocalFiles(path) {
  if (!props.localState?.files) return []
  const prefix = path ? path + '/' : ''
  const contents = new Map()

  for (const f of props.localState.files) {
    if (!f.path.startsWith(prefix)) continue
    const relative = f.path.slice(prefix.length)
    const parts = relative.split('/')

    if (parts.length > 1) {
      const dirName = parts[0]
      if (!contents.has(dirName)) {
        contents.set(dirName, {
          encName: dirName,
          decName: dirName,
          path: prefix + dirName,
          is_dir: true,
          size: 0,
        })
      }
    } else {
      contents.set(f.name, {
        encName: f.name,
        decName: f.name,
        path: f.path,
        is_dir: false,
        size: 0,
      })
    }
  }

  return Array.from(contents.values())
}

function handleClick(item) {
  const path = getRoutePath()

  if (item.is_dir) {
    if (props.mode === 'alist') {
      const encPath = path === '/' ? '/' + item.encName : path + '/' + item.encName
      router.push('/browse' + encPath)
    } else {
      const newPath = item.path || (path ? path + '/' + item.encName : item.encName)
      currentPath.value = newPath
    }
  } else {
    const filePath = props.mode === 'alist'
      ? (path === '/' ? '/' + item.encName : path + '/' + item.encName)
      : item.path

    const type = getFileType(item.decName)
    if (props.mode === 'alist') {
      if (type === 'video') router.push('/play' + filePath)
      else if (type === 'audio') router.push('/listen' + filePath)
      else if (type === 'image') router.push('/view' + filePath)
    } else {
      if (type === 'video') router.push('/play/local/' + encodeURIComponent(filePath))
      else if (type === 'audio') router.push('/listen/local/' + encodeURIComponent(filePath))
      else if (type === 'image') router.push('/view/local/' + encodeURIComponent(filePath))
    }
  }
}

if (props.mode === 'alist') {
  watch(() => route.params.path, () => loadCurrentPath(), { immediate: true })
} else {
  watch([currentPath, () => props.localState?.files], () => loadCurrentPath(), { immediate: true })
}
</script>

<style scoped>
.file-browser {
  flex: 1;
  position: relative;
  min-height: 100%;
}

.hidden-file-input {
  display: none;
}

/* ── Floating Actions ── */
.upload-fab,
.refresh-fab {
  position: fixed;
  z-index: 50;
  border: 1px solid var(--border-glass);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
  transition: all 0.2s ease;
}

.upload-fab {
  right: 28px;
  bottom: var(--fab-bottom, 76px);
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.95), rgba(59, 130, 246, 0.92));
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}

.refresh-fab {
  right: 28px;
  bottom: 20px;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--bg-card);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}

.upload-fab:hover,
.refresh-fab:hover {
  border-color: var(--border-glass-hover);
  box-shadow: 0 6px 28px rgba(0, 0, 0, 0.4);
  transform: translateY(-1px);
}

.upload-fab:active,
.refresh-fab:active {
  transform: scale(0.97);
}

.upload-fab:disabled,
.refresh-fab:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

.refresh-fab.spinning {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

@media (max-width: 600px) {
  .upload-fab,
  .refresh-fab {
    right: 16px;
  }

  .upload-fab {
    bottom: var(--fab-bottom-mobile, 68px);
    width: 40px;
    height: 40px;
    font-size: 16px;
  }

  .refresh-fab {
    bottom: 16px;
    width: 40px;
    height: 40px;
    font-size: 16px;
  }
}
</style>

<style>
/* ── Modal Card Override ──
   NOTE: with preset="card" the ROOT element is itself the .n-card, so a
   descendant selector like ".ske-modal .n-card" never matches it. Target
   .ske-modal directly (keeping the descendant form for safety) — otherwise the
   surface falls back to the translucent Card theme colour and the page
   underneath bleeds through the dialog text. */
.ske-modal,
.ske-modal .n-card {
  background-color: #161632 !important;
  border: 1px solid rgba(255, 255, 255, 0.15) !important;
  border-radius: 16px !important;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.7) !important;
}

.ske-modal .n-card-header {
  padding-bottom: 16px !important;
}

.ske-modal .n-card-header__main {
  font-size: 17px !important;
  font-weight: 700 !important;
}

.ske-modal .n-card__content {
  padding-top: 0 !important;
}

.ske-modal .n-card__action {
  padding-top: 16px !important;
}

.modal-desc {
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.6;
}

.modal-option {
  margin-top: 14px;
}

.upload-file-list {
  max-height: 180px;
  overflow-y: auto;
  margin-top: 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.03);
}

.upload-file-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.upload-file-item:last-child {
  border-bottom: none;
}

.upload-file-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
}

.upload-file-size {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.upload-progress {
  margin-top: 12px;
  margin-bottom: 0;
  color: var(--text-secondary);
  font-size: 13px;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.modal-btn {
  min-width: 80px;
  height: 36px;
  border-radius: 10px !important;
  font-weight: 600;
  font-size: 13px;
}

.modal-btn.n-button--quaternary-type {
  background: rgba(255, 255, 255, 0.06) !important;
  border: 1px solid rgba(255, 255, 255, 0.12) !important;
  color: var(--text-secondary) !important;
}

.modal-btn.n-button--quaternary-type:hover {
  background: rgba(255, 255, 255, 0.1) !important;
  border-color: rgba(255, 255, 255, 0.2) !important;
  color: var(--text-primary) !important;
}

/* ── Context Menu ── */
.ctx-menu {
  position: fixed;
  z-index: 9999;
  min-width: 140px;
  padding: 6px 0;
  border-radius: 10px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  transform-origin: top left;
}

/* Entrance: the panel scales out of the cursor, items follow in sequence. */
.ctx-pop-enter-active {
  transition: opacity 0.12s ease-out, transform 0.14s cubic-bezier(0.16, 1, 0.3, 1);
}

.ctx-pop-leave-active {
  transition: opacity 0.09s ease-in, transform 0.09s ease-in;
}

.ctx-pop-enter-from,
.ctx-pop-leave-to {
  opacity: 0;
  transform: scale(0.9) translateY(-4px);
}

.ctx-pop-enter-active .ctx-item {
  animation: ctx-item-in 0.16s ease-out both;
}

.ctx-pop-enter-active .ctx-item:nth-child(2) { animation-delay: 0.03s; }
.ctx-pop-enter-active .ctx-item:nth-child(3) { animation-delay: 0.06s; }
.ctx-pop-enter-active .ctx-item:nth-child(4) { animation-delay: 0.09s; }

@keyframes ctx-item-in {
  from {
    opacity: 0;
    transform: translateX(-5px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .ctx-pop-enter-active,
  .ctx-pop-leave-active {
    transition-duration: 0.01ms;
  }

  .ctx-pop-enter-active .ctx-item {
    animation: none;
  }
}

.ctx-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px;
  font-size: 13px;
  color: var(--text-primary);
  cursor: pointer;
  transition: background 0.12s;
}

.ctx-item:hover {
  background: rgba(255, 255, 255, 0.08);
}

.ctx-item.ctx-danger:hover {
  background: rgba(239, 68, 68, 0.15);
  color: #ef4444;
}
</style>
