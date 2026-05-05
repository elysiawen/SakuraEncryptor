<template>
  <div class="file-browser" @contextmenu.capture.prevent="handleBrowserContextMenu">
    <FileGrid
      :items="items"
      :loading="loading"
      :error="error"
      @click="handleClick"
      @retry="loadCurrentPath"
      @contextmenu="openMenu"
    />

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
      <div
        v-if="menu.show"
        class="ctx-menu glass-card"
        :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
        @click.stop
      >
        <div v-if="!menu.item" class="ctx-item" @click="startNewFolder">
          <span>📁</span> 新建文件夹
        </div>
        <div v-if="menu.item" class="ctx-item" @click="startRename">
          <span>✏️</span> 重命名
        </div>
        <div v-if="menu.item" class="ctx-item ctx-danger" @click="confirmDelete">
          <span>🗑️</span> 删除
        </div>
      </div>
    </Teleport>

    <!-- New Folder Dialog -->
    <n-modal v-model:show="mkdir.show" preset="card" title="新建文件夹" class="ske-modal" style="max-width: 380px;">
      <n-input
        v-model:value="mkdir.name"
        placeholder="文件夹名称"
        @keyup.enter="doMkdir"
        :input-props="{ autocomplete: 'off' }"
      />
      <template #action>
        <div class="modal-actions">
          <n-button class="modal-btn" quaternary @click="mkdir.show = false">取消</n-button>
          <n-button class="modal-btn" type="primary" :loading="mkdir.loading" @click="doMkdir">创建</n-button>
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
import { ref, reactive, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FileGrid from './FileGrid.vue'
import { getNameKeyFromSession, decryptName, encryptName } from '../../composables/useCrypto.js'
import { globalState } from '../../composables/useGlobalState.js'
import { getFileType } from '../../composables/useFileDetection.js'
import { renameItem, deleteItems, makeDir } from '../../composables/useAList.js'

const props = defineProps({
  mode: { type: String, required: true },
  listFn: { type: Function },
  localState: { type: Object },
})

const route = useRoute()
const router = useRouter()

const items = ref([])
const loading = ref(false)
const error = ref('')
const currentPath = ref('')

// ── Context Menu ──
const menu = reactive({ show: false, x: 0, y: 0, item: null })

function openMenu(e, item) {
  const pad = 8
  let x = e.clientX
  let y = e.clientY
  if (x + 160 > window.innerWidth) x = window.innerWidth - 160 - pad
  if (y + 120 > window.innerHeight) y = window.innerHeight - 120 - pad
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

onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))

// ── New Folder ──
const mkdir = reactive({ show: false, name: '', loading: false })

function startNewFolder() {
  mkdir.name = ''
  mkdir.show = true
  closeMenu()
}

async function doMkdir() {
  if (!mkdir.name.trim()) return
  mkdir.loading = true
  try {
    const nameKey = await getNameKeyFromSession()
    const path = getRoutePath()

    if (props.mode === 'alist' && nameKey) {
      const encName = await encryptName(mkdir.name.trim(), nameKey)
      await makeDir(path + '/' + encName)
    }

    mkdir.show = false
    await loadCurrentPath(false, true)
  } catch (err) {
    error.value = err.message || '创建文件夹失败'
  } finally {
    mkdir.loading = false
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

// ── Route helpers ──
function getRoutePath() {
  if (props.mode === 'local') return currentPath.value || ''
  const p = route.params.path
  if (!p) return '/'
  const joined = Array.isArray(p) ? p.join('/') : p
  return '/' + joined
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

/* ── Refresh FAB ── */
.refresh-fab {
  position: fixed;
  bottom: 28px;
  right: 28px;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 1px solid var(--border-glass);
  background: var(--bg-card);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
  transition: all 0.2s ease;
  z-index: 50;
}

.refresh-fab:hover {
  border-color: var(--border-glass-hover);
  box-shadow: 0 6px 28px rgba(0, 0, 0, 0.4);
  transform: scale(1.08);
}

.refresh-fab:active {
  transform: scale(0.95);
}

.refresh-fab.spinning {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

@media (max-width: 600px) {
  .refresh-fab {
    bottom: 20px;
    right: 16px;
    width: 40px;
    height: 40px;
    font-size: 16px;
  }
}
</style>

<style>
/* ── Modal Card Override ── */
.ske-modal .n-card {
  background: rgba(22, 22, 50, 0.98) !important;
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
