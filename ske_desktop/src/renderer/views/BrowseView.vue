<template>
  <!-- A drop anywhere in the list uploads into the folder currently shown. -->
  <div
    class="browse"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent
    @dragleave="onDragLeave"
    @drop.prevent="onDrop"
  >
    <EmptyState
      v-if="!activeProfile"
      icon="☁"
      title="尚未选择云端配置"
      description="云端库通过 AList 读取加密目录。添加一套账号后即可在应用内浏览并直接播放，密码不会离开本机。"
      action-text="前往配置"
      @action="$router.push('/profiles')"
    />

    <div v-else-if="cloud.connecting" class="centre">
      <span class="spinner" />
      <span>正在连接 {{ activeProfile.name }}…</span>
    </div>

    <EmptyState
      v-else-if="cloud.error && !cloud.connected"
      icon="⚠"
      title="无法连接云端"
      :description="cloud.error"
      action-text="重试"
      @action="reconnect"
    />

    <template v-else>
      <div class="toolbar">
        <nav class="crumbs">
          <template v-for="(crumb, index) in crumbs()" :key="crumb.path">
            <span v-if="index" class="sep">/</span>
            <button
              class="crumb"
              type="button"
              :disabled="index === crumbs().length - 1"
              @click="go(crumb.path)"
            >
              {{ crumb.label }}
            </button>
          </template>
        </nav>

        <div class="tools">
          <span v-if="activeBatch.length" class="upload-chip" title="上传进度">
            上传中 {{ activeBatch.length }} 个 · {{ uploadPercent }}%
          </span>
          <span v-if="cloud.entries.length" class="count">{{ cloud.entries.length }} 项</span>
          <button
            class="ghost sm"
            type="button"
            :class="{ on: selectMode }"
            :disabled="!cloud.entries.length && !selectMode"
            @click="toggleSelectMode"
          >
            {{ selectMode ? '完成' : '多选' }}
          </button>
          <button class="ghost sm" type="button" @click="pickUploads">上传</button>
          <button class="ghost sm" type="button" :disabled="cloud.loading" @click="reload">刷新</button>
          <div class="segmented">
            <button
              type="button"
              :class="{ on: view === 'grid' }"
              title="网格视图"
              @click="view = 'grid'"
            >
              网格
            </button>
            <button
              type="button"
              :class="{ on: view === 'list' }"
              title="列表视图"
              @click="view = 'list'"
            >
              列表
            </button>
          </div>
        </div>
      </div>

      <div v-if="selectMode" class="sel-bar">
        <span class="sel-count">已选 {{ selected.size }} 项</span>
        <div class="sel-actions">
          <button class="ghost sm" type="button" @click="selectAll">全选</button>
          <button
            class="ghost sm"
            type="button"
            :disabled="!selected.size"
            @click="clearSelection"
          >
            取消选择
          </button>
          <button
            class="ghost sm"
            type="button"
            :disabled="!downloadableSelected.length"
            @click="downloadSelected"
          >
            下载
          </button>
          <button
            class="danger sm"
            type="button"
            :disabled="!selected.size"
            @click="openDeleteSelected"
          >
            删除
          </button>
        </div>
      </div>

      <!-- A failed listing after connecting used to be silent: the view stayed
           on the old folder and the click looked ignored. -->
      <div v-if="cloud.error" class="inline-error">
        <span>{{ cloud.error }}</span>
        <button class="ghost sm" type="button" @click="reload">重试</button>
      </div>

      <div class="body" @click.self="clearSelection" @contextmenu.prevent="openMenu($event, null)">
        <div v-if="cloud.loading && !cloud.entries.length" class="centre"><span class="spinner" /></div>

        <EmptyState
          v-else-if="!cloud.entries.length"
          icon="🗂"
          title="这个目录是空的"
          description="加密文件与普通文件都会出现在这里。加密条目会标注出来，只有输入正确的媒体密码才能显示文件名与内容。"
        />

        <div v-else-if="view === 'grid'" class="grid" @click.self="clearSelection">
          <button
            v-for="(entry, i) in cloud.entries"
            :key="entry.storedName"
            class="tile stagger-in"
            :class="{ selected: selected.has(entry.path) }"
            :style="{ '--i': i }"
            type="button"
            :title="entry.name"
            :aria-pressed="selectMode ? selected.has(entry.path) : undefined"
            @click="onRowClick($event, entry)"
            @contextmenu.prevent.stop="openMenu($event, entry)"
          >
            <div class="art" :class="{ pending: isPending(entry) }">
              <span v-if="selectMode" class="check" aria-hidden="true">
                {{ selected.has(entry.path) ? '✓' : '' }}
              </span>
              <!-- The glyph is always painted underneath: decoding a thumbnail
                   ahead of opening means the tile is never an empty hole. -->
              <span class="glyph" aria-hidden="true">
                <IconImage v-if="entry.kind === 'image'" />
                <template v-else>{{ glyph(entry) }}</template>
              </span>
              <img
                v-if="thumbSource(entry)"
                :class="{ ready: isThumbLoaded(entry) }"
                :src="thumbSource(entry)"
                loading="lazy"
                alt=""
                @load="markThumbLoaded(entry)"
                @error="noteThumbFailure(entry)"
              />
              <!-- Overlays follow the web grid: a play ring for video, a lock for
                   encrypted entries, and the size in the corner. Text tags are a
                   list-view affordance; a grid tile only has room for marks. -->
              <span v-if="entry.kind === 'video'" class="play-badge" aria-hidden="true">▶</span>
              <span
                v-if="entry.encrypted && !entry.locked"
                class="badge badge-file enc lock-badge"
                title="加密文件"
              >
                🔒
              </span>
              <span v-if="!entry.isDir && entry.size" class="badge badge-file size-badge mono">
                {{ formatSize(entry.size) }}
              </span>
            </div>
            <div class="label">{{ entry.name }}</div>
          </button>
        </div>

        <div v-else class="rows" @click.self="clearSelection">
          <button
            v-for="(entry, i) in cloud.entries"
            :key="entry.storedName"
            class="row-item stagger-in"
            :class="{ selected: selected.has(entry.path) }"
            :style="{ '--i': i }"
            type="button"
            :aria-pressed="selectMode ? selected.has(entry.path) : undefined"
            @click="onRowClick($event, entry)"
            @contextmenu.prevent.stop="openMenu($event, entry)"
          >
            <span v-if="selectMode" class="check" aria-hidden="true">
              {{ selected.has(entry.path) ? '✓' : '' }}
            </span>
            <span class="glyph small">
              <IconImage v-if="entry.kind === 'image'" />
              <template v-else>{{ glyph(entry) }}</template>
            </span>
            <span class="name">{{ entry.name }}</span>
            <span class="badge badge-file" :class="tagClass(entry)">{{ tagLabel(entry) }}</span>
            <span class="size mono">{{ entry.isDir ? '—' : formatSize(entry.size) }}</span>
          </button>
        </div>
      </div>

      <ContextMenu
        :show="menu.show"
        :x="menu.x"
        :y="menu.y"
        :items="menu.items"
        @close="menu.show = false"
      />

      <PromptDialog
        :visible="mkdir.show"
        title="新建文件夹"
        description="名称会被加密成 token 存进云端目录树；取消勾选则使用明文名称。"
        placeholder="文件夹名称"
        confirm-text="创建"
        checkbox-label="加密名称"
        :checked-by-default="true"
        :busy="mkdir.busy"
        :error="mkdir.error"
        @cancel="mkdir.show = false"
        @submit="submitMkdir"
      />

      <PromptDialog
        :visible="rename.show"
        :title="rename.target?.isDir ? '重命名文件夹' : '重命名'"
        description="加密条目会重新加密为新名字，未加密的条目保持明文。"
        placeholder="新名称"
        :initial-value="rename.target?.name || ''"
        confirm-text="重命名"
        :busy="rename.busy"
        :error="rename.error"
        @cancel="rename.show = false"
        @submit="submitRename"
      />

      <ConfirmDialog
        :visible="removal.show"
        title="确认删除"
        :description="removalDescription"
        :busy="removal.busy"
        :error="removal.error"
        @cancel="removal.show = false"
        @confirm="submitDelete"
      />

      <UploadDialog
        :visible="upload.show"
        :folder="cloud.path"
        :files="upload.files"
        :busy="upload.busy"
        :error="upload.error"
        @cancel="upload.show = false"
        @submit="submitUpload"
      />

      <!-- Drop target overlay: visual only, so it must not intercept the drag. -->
      <div v-if="dragging" class="drop-veil">
        <div class="drop-card">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
            <path d="M12 4v11" />
            <path d="M8 11l4 4 4-4" />
            <path d="M4 17v2.5A1.5 1.5 0 0 0 5.5 21h13a1.5 1.5 0 0 0 1.5-1.5V17" />
          </svg>
          <p>松开以上传到「{{ crumbs().at(-1)?.label || '/' }}」</p>
          <span>确认后按你的设置加密上传</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import ContextMenu from '../components/ContextMenu.vue'
import EmptyState from '../components/EmptyState.vue'
import IconImage from '../components/IconImage.vue'
import PromptDialog from '../components/PromptDialog.vue'
import UploadDialog from '../components/UploadDialog.vue'
import { activeProfile, toast } from '../state/session.js'
import { enqueueDownload } from '../state/downloads.js'
import { enqueueUploads, prepareUploads, uploads } from '../state/uploads.js'
import {
  ensureFallback,
  isThumbLoaded,
  markThumbLoaded,
  noteThumbFailure,
  resetThumbnailCache,
  thumbSource,
} from '../state/thumbnails.js'
import {
  cloud,
  crumbs,
  ensureConnected,
  formatSize,
  load,
  makeFolder,
  playable,
  removeEntries,
  renameEntry,
  requirePlayable,
} from '../state/cloud.js'
import {
  copyable,
  copyEntryLink,
  listExternalPlayers,
  openEntryExternally,
  playerMenuItems,
} from '../state/media-link.js'
import { playEntry } from '../state/player.js'

const view = ref('grid')

/** True while a thumbnail is on its way but not painted yet. */
function isPending(entry) {
  return !!thumbSource(entry) && !isThumbLoaded(entry)
}

// The tile bookkeeping lives in `state/thumbnails.js` on purpose: it survives
// folder navigation and view switches, so coming back to a folder shows the
// previews immediately instead of replaying their fade-in.
//
// A new cloud session does invalidate all of it — the keys embed the session id
// and the old decrypt sessions must be released.
watch(() => cloud.sessionId, resetThumbnailCache)

// Plain files carry no `/thumb/` URL at all, so no <img> error would ever start
// their fallback. Resolve those from the listing instead; the queues in
// `state/thumbnails.js` still cap the work at three images / one video at a time.
watch(
  [() => cloud.entries, view],
  ([entries]) => {
    // Rows show no artwork, so the work only pays off in the grid.
    if (view.value !== 'grid') return
    for (const entry of entries) {
      if (entry.isDir || entry.locked || entry.thumbUrl) continue
      if (entry.kind !== 'image' && entry.kind !== 'video') continue
      ensureFallback(entry).catch(() => {})
    }
  },
  { immediate: true },
)

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  ensureConnected()
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

// Switching profiles must reconnect; otherwise the listing would silently keep
// showing the previous account's files.
watch(
  () => activeProfile.value?.id,
  () => ensureConnected(),
)

async function reconnect() {
  await ensureConnected({ force: true })
}

async function go(path) {
  if (path === cloud.path) return
  await load(path)
}

// Manual refresh, same semantics as the web client: force AList to re-scan the
// folder instead of answering from its cache. Navigating keeps using the cache.
async function reload() {
  await load(cloud.path, { refresh: true })
}

// ── Selection ──
// Picking is an explicit mode shared by the grid and the list: the toolbar
// button turns clicks into marks instead of opens, and Shift extends the range
// from the last entry touched. Keys are plain paths, unique within a folder.
const selectMode = ref(false)
const selected = reactive(new Set())
const anchor = ref('')

const selectedEntries = computed(() =>
  cloud.entries.filter((entry) => selected.has(entry.path)),
)
const downloadableSelected = computed(() => selectedEntries.value.filter(isDownloadable))

// Navigating or switching profiles ends the gesture: the marks belong to the
// folder and session they were made in.
watch(() => cloud.path, exitSelectMode)
watch(() => cloud.sessionId, exitSelectMode)

// A reload can drop entries that are still marked, so the set is pruned to what
// the listing actually returned instead of leaking stale keys.
watch(
  () => cloud.entries,
  () => {
    const alive = new Set(cloud.entries.map((entry) => entry.path))
    for (const key of [...selected]) if (!alive.has(key)) selected.delete(key)
    if (!alive.has(anchor.value)) anchor.value = ''
  },
)

function toggleSelectMode() {
  if (selectMode.value) exitSelectMode()
  else {
    selectMode.value = true
    anchor.value = ''
  }
}

function exitSelectMode() {
  selectMode.value = false
  clearSelection()
}

function selectAll() {
  for (const entry of cloud.entries) selected.add(entry.path)
  if (cloud.entries.length) anchor.value = cloud.entries[0].path
}

function clearSelection() {
  if (selected.size) selected.clear()
  anchor.value = ''
}

function toggleSelection(entry) {
  if (selected.has(entry.path)) selected.delete(entry.path)
  else selected.add(entry.path)
  anchor.value = entry.path
}

function extendSelection(entry) {
  const entries = cloud.entries
  const to = entries.findIndex((item) => item.path === entry.path)
  if (to < 0) return

  const from = entries.findIndex((item) => item.path === anchor.value)
  if (from < 0) {
    selected.add(entry.path)
    anchor.value = entry.path
    return
  }

  // The anchor is kept so dragging the far end back and forth stays stable.
  const [start, end] = from <= to ? [from, to] : [to, from]
  selected.clear()
  for (let index = start; index <= end; index += 1) selected.add(entries[index].path)
}

function onRowClick(event, entry) {
  if (!selectMode.value) {
    clearSelection()
    activate(entry)
    return
  }

  // Marking never opens: a click toggles, Shift grabs the range in one go.
  if (event.shiftKey) extendSelection(entry)
  else toggleSelection(entry)
}

function onKeydown(event) {
  const tag = event.target?.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || event.target?.isContentEditable) return

  if (event.key === 'Escape' && (selectMode.value || selected.size)) {
    exitSelectMode()
    return
  }

  const selectAllPressed = (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'a'
  if (selectAllPressed && cloud.entries.length) {
    event.preventDefault()
    selectMode.value = true
    selectAll()
  }
}

function activate(entry) {
  if (entry.isDir) {
    go(entry.path)
    return
  }
  if (!requirePlayable(entry)) return

  // Same folder, same media kind: the playlist follows what the user is doing.
  const playlist = cloud.entries
    .filter((item) => playable(item) && item.kind === entry.kind)
    .map((item) => ({
      mode: 'cloud',
      sessionId: cloud.sessionId,
      path: item.path,
      name: item.name,
      kind: item.kind,
    }))
  const index = playlist.findIndex((item) => item.path === entry.path)

  const item = {
    mode: 'cloud',
    sessionId: cloud.sessionId,
    path: entry.path,
    name: entry.name,
    kind: entry.kind,
  }

  playEntry(item, { playlist, index }).catch((err) => toast(err.message, 'error'))
}

async function download(entry) {
  try {
    await enqueueDownload({
      mode: 'cloud',
      sessionId: cloud.sessionId,
      path: entry.path,
      name: entry.name,
    })
    toast(`已加入下载：${entry.name}`, 'ok')
  } catch (err) {
    toast(err.message, 'error')
  }
}

/** Folders have no body to fetch; a locked container failed the key check. */
function isDownloadable(entry) {
  return !entry.isDir && !entry.locked
}

/** Queue a batch one by one: `enqueueDownload` reports failures per file. */
async function downloadMany(entries) {
  let added = 0
  let failure = null

  for (const entry of entries) {
    try {
      await enqueueDownload({
        mode: 'cloud',
        sessionId: cloud.sessionId,
        path: entry.path,
        name: entry.name,
      })
      added += 1
    } catch (err) {
      failure = failure || err
    }
  }

  if (added) toast(`已加入下载：${added} 个文件`, 'ok')
  if (failure) toast(failure.message, 'error')
}

function downloadSelected() {
  const files = downloadableSelected.value
  if (!files.length) {
    toast('选中的项目里没有可下载的文件', 'error')
    return
  }
  downloadMany(files)
}

// ── Context menu ──
// Actions live on the menu instead of a hover icon: an entry keeps its tile
// clean, and the same gesture works in both views. The option set is the web
// client's minus 打开: a click already opens, so the menu carries only
// new folder on the background and download/rename/delete on an entry.
const menu = reactive({ show: false, x: 0, y: 0, items: [] })
const MENU_WIDTH = 184
const MENU_ITEM_HEIGHT = 32
/** A hairline plus its margins; only the menu's own bounds calculation needs it. */
const MENU_SEPARATOR_HEIGHT = 9
const MENU_PADDING = 12

/**
 * Flatten action groups into menu items, with a divider between them.
 *
 * Categories are what make a six-item menu scannable: playing, transferring and
 * managing a file are different kinds of act and should not read as one list.
 */
function grouped(groups) {
  const items = []
  for (const group of groups) {
    if (!group.length) continue
    if (items.length) items.push({ separator: true })
    items.push(...group)
  }
  return items
}

function buildMenu(entry, players = []) {
  if (!entry) {
    return grouped([
      [{ label: '上传', icon: '⤒', action: pickUploads }],
      [
        { label: '新建文件夹', icon: '📁', action: openMkdir },
        { label: '刷新', icon: '🔄', action: reload },
      ],
    ])
  }

  // Right-clicking a marked entry acts on the whole selection; anything else is
  // a single target. Renaming has no batch form, so it is single-entry only.
  const marked = selected.has(entry.path) && selected.size > 1
  const targets = marked ? [...selectedEntries.value] : [entry]

  if (targets.length > 1) {
    const files = targets.filter(isDownloadable)
    return grouped([
      [
        {
          label: `下载 ${files.length} 个文件`,
          icon: '⤓',
          disabled: !files.length,
          action: () => downloadMany(files),
        },
      ],
      [{ label: '取消选择', icon: '✕', action: clearSelection }],
      [
        {
          label: `删除 ${targets.length} 项`,
          icon: '🗑️',
          danger: true,
          action: () => openDeleteMany(targets),
        },
      ],
    ])
  }

  // File actions first, playback in the middle, and destruction on its own at
  // the bottom where a mis-aimed click cannot reach it on the way to something
  // else. Opening is a left click, so the menu carries only the extras.
  const file = []
  if (!entry.isDir) {
    file.push({
      label: '下载',
      icon: '⤓',
      // A container that failed the key check has nothing to write out.
      disabled: entry.locked,
      action: () => download(entry),
    })
  }
  file.push({ label: '重命名', icon: '✏️', action: () => openRename(entry) })

  const playback = []
  if (copyable(entry)) {
    playback.push(
      { label: '复制播放链接', icon: '🔗', disabled: entry.locked, action: () => copyLink(entry) },
      {
        label: '外部播放器',
        icon: '🎬',
        disabled: entry.locked,
        // Second level: which player. Detection happens in the main process.
        children: playerMenuItems(players, (player) => playExternal(entry, player.path)),
      },
    )
  }

  const destroy = [{ label: '删除', icon: '🗑️', danger: true, action: () => openDelete(entry) }]

  return grouped([file, playback, destroy])
}

/**
 * Copy the URL of a decrypted stream.
 *
 * The link is only useful while the session behind it stays open, so the main
 * side keeps it alive and closes the previous one; quitting the app or copying
 * another entry ends it.
 */
async function copyLink(entry) {
  try {
    await copyEntryLink({
      mode: 'cloud',
      sessionId: cloud.sessionId,
      path: entry.path,
      name: entry.name,
    })
    toast('播放链接已复制，可粘贴到 mpv / VLC 等播放器；复制其他文件或退出应用后失效', 'ok')
  } catch (err) {
    toast(err.message, 'error')
  }
}

/**
 * Hand the stream to mpv/VLC/PotPlayer — what the copied link exists for.
 *
 * The session stays open while the player reads it; launching another one
 * closes this, and quitting the app ends both.
 */
async function playExternal(entry, exe = '') {
  try {
    const res = await openEntryExternally(
      {
        mode: 'cloud',
        sessionId: cloud.sessionId,
        path: entry.path,
        name: entry.name,
      },
      exe,
    )
    toast(`已用 ${res.player || '外部播放器'} 打开；播放期间请保持应用运行`, 'ok')
  } catch (err) {
    toast(err.message, 'error')
  }
}

async function openMenu(event, entry) {
  if (entry && selectMode.value) {
    // Right-click outside the marks targets that entry alone; inside them the
    // batch is kept so the menu can act on the whole selection.
    if (!selected.has(entry.path)) {
      selected.clear()
      selected.add(entry.path)
      anchor.value = entry.path
    }
  } else if (!selectMode.value) {
    // Outside picking mode a right-click is a single-entry gesture.
    clearSelection()
  }

  // Which players exist is a main-process question (it caches the detection),
  // so the menu is built after that round trip — a few milliseconds.
  const players = entry ? await listExternalPlayers() : []
  const items = buildMenu(entry, players)
  // Dividers are shorter than rows, and a menu clamped as if they were rows
  // would stop short of the window edge.
  const height =
    items.reduce(
      (total, item) => total + (item.separator ? MENU_SEPARATOR_HEIGHT : MENU_ITEM_HEIGHT),
      0,
    ) + MENU_PADDING

  menu.x = Math.max(8, Math.min(event.clientX, window.innerWidth - MENU_WIDTH - 8))
  menu.y = Math.max(8, Math.min(event.clientY, window.innerHeight - height - 8))
  menu.items = items
  menu.show = true
}

// ── Uploads ──
// The queue lives in the main process; this view only reads snapshots pushed by
// it, so closing the window or switching folders does not cancel anything.
const upload = reactive({ show: false, files: [], busy: false, error: '', ids: [] })

const activeBatch = computed(() =>
  uploads.items.filter((item) => item.status === 'queued' || item.status === 'running'),
)

const uploadPercent = computed(() => {
  const total = activeBatch.value.reduce((sum, item) => sum + item.totalBytes, 0)
  const done = activeBatch.value.reduce((sum, item) => sum + item.doneBytes, 0)
  return total ? Math.min(100, Math.round((done / total) * 100)) : 0
})

// ── Dropping files ──
// A drop is the quickest route to an upload and the only one that skips the
// picker. The overlay tracks a depth counter rather than a flag: dragenter and
// dragleave fire for every element the pointer crosses on the way in.
const dragDepth = ref(0)
const dragging = computed(() => dragDepth.value > 0 && !!cloud.connected && !upload.show)

function onDragEnter(event) {
  if (!cloud.connected) return
  // Only a drag that carries files from the OS; dragging a row within the list
  // does not, and must not raise the overlay.
  if (!event.dataTransfer?.types?.includes('Files')) return
  dragDepth.value += 1
}

function onDragLeave() {
  if (dragDepth.value > 0) dragDepth.value -= 1
}

/**
 * Dropped files go through the same confirmation as the picker: the drop
 * decides *what*, the dialog still decides *how* (encrypt or not).
 */
async function onDrop(event) {
  dragDepth.value = 0
  if (!cloud.connected) return

  // Electron 32 removed File.path; the bridged webUtils call is the only way
  // to turn a dropped File into a real path.
  const paths = [...(event.dataTransfer?.files || [])]
    .map((file) => window.ske.files?.pathForFile?.(file) || '')
    .filter(Boolean)

  if (!paths.length) return

  try {
    const files = await prepareUploads(paths)
    if (!files.length) {
      toast('拖入的内容里没有可上传的文件（文件夹不会被展开）', 'error')
      return
    }

    upload.files = files
    upload.error = ''
    upload.show = true

    if (files.length < paths.length) {
      toast(`已忽略 ${paths.length - files.length} 个文件夹`, 'error')
    }
  } catch (err) {
    toast(err.message, 'error')
  }
}

async function pickUploads() {
  const res = await window.ske.dialog.pickUploadFiles()
  if (!res?.ok) {
    toast(res?.error || '无法打开文件选择器', 'error')
    return
  }
  if (!res.files?.length) return

  upload.files = res.files
  upload.error = ''
  upload.show = true
}

async function submitUpload({ encrypt }) {
  upload.busy = true
  upload.error = ''
  try {
    const jobs = await enqueueUploads({
      sessionId: cloud.sessionId,
      path: cloud.path,
      files: upload.files,
      encrypt,
    })
    upload.ids = (jobs || []).map((job) => job.id)
    upload.show = false
    toast(`已加入上传队列：${upload.ids.length} 个文件`, 'ok')
  } catch (err) {
    upload.error = err.message
  } finally {
    upload.busy = false
  }
}

/**
 * Refresh once this batch has settled.
 *
 * The new entries must appear without a manual refresh, and a failure must not
 * disappear into a progress bar that simply stops moving — the first error is
 * surfaced instead. Snapshots can be cleared from another view, hence the
 * length check before treating a batch as finished.
 */
watch(
  () => uploads.items.map((item) => `${item.id}:${item.status}`).join(','),
  () => {
    if (!upload.ids.length) return

    const batch = uploads.items.filter((item) => upload.ids.includes(item.id))
    if (batch.length < upload.ids.length) return
    if (batch.some((item) => item.status === 'queued' || item.status === 'running')) return

    const failed = batch.filter((item) => item.status === 'failed')
    upload.ids = []
    if (failed.length) toast(`${failed.length} 个文件上传失败：${failed[0].error}`, 'error')
    else toast('上传完成', 'ok')
    reload()
  },
)

// ── Dialogs ──
const mkdir = reactive({ show: false, busy: false, error: '' })
const rename = reactive({ show: false, target: null, busy: false, error: '' })
const removal = reactive({ show: false, targets: [], busy: false, error: '' })

const removalDescription = computed(() => {
  const targets = removal.targets
  if (!targets.length) return ''
  if (targets.length === 1) return `「${targets[0].name}」将从云端删除，此操作不可撤销。`
  return `选中的 ${targets.length} 项将从云端删除，此操作不可撤销。`
})

function openMkdir() {
  mkdir.error = ''
  mkdir.show = true
}

async function submitMkdir({ text, checked }) {
  mkdir.busy = true
  mkdir.error = ''
  try {
    await makeFolder(cloud.path, text, checked)
    mkdir.show = false
    toast(`已创建文件夹：${text}`, 'ok')
  } catch (err) {
    mkdir.error = err.message
  } finally {
    mkdir.busy = false
  }
}

function openRename(entry) {
  rename.target = entry
  rename.error = ''
  rename.show = true
}

async function submitRename({ text }) {
  const target = rename.target
  if (!target) return
  if (text === target.name) {
    rename.show = false
    return
  }

  rename.busy = true
  rename.error = ''
  try {
    await renameEntry(target.path, text)
    rename.show = false
    toast(`已重命名为：${text}`, 'ok')
  } catch (err) {
    rename.error = err.message
  } finally {
    rename.busy = false
  }
}

function openDelete(entry) {
  openDeleteMany([entry])
}

function openDeleteMany(entries) {
  if (!entries.length) return
  removal.targets = [...entries]
  removal.error = ''
  removal.show = true
}

function openDeleteSelected() {
  openDeleteMany(selectedEntries.value)
}

async function submitDelete() {
  const targets = [...removal.targets]
  if (!targets.length) return

  removal.busy = true
  removal.error = ''
  try {
    // One failed path must not abandon the rest, so the batch reports what
    // fell over only after every entry has been attempted.
    const failed = await removeEntries(targets.map((target) => target.path))

    if (failed.length === targets.length) {
      removal.error = failed[0].error
      return
    }

    removal.show = false
    exitSelectMode()

    if (failed.length) {
      toast(`${failed.length} 项删除失败：${failed[0].error}`, 'error')
      return
    }
    toast(targets.length === 1 ? `已删除：${targets[0].name}` : `已删除 ${targets.length} 项`, 'ok')
  } catch (err) {
    removal.error = err.message
  } finally {
    removal.busy = false
  }
}

/**
 * Every entry is marked, encrypted or not: a folder can hold hand-uploaded
 * plain files next to containers, and the difference decides whether opening it
 * needs a password at all. Folders have no `.ske` marker, so their flag comes
 * from the main process (a name that decrypts).
 */
function tagLabel(entry) {
  if (entry.locked) return '无法解密'
  return entry.encrypted ? '加密' : '未加密'
}

function tagClass(entry) {
  if (entry.locked) return 'warn'
  return entry.encrypted ? 'enc' : 'plain'
}

/** Emoji marks for every kind except images, which draw `IconImage` instead. */
function glyph(entry) {
  if (entry.locked) return '🔒'
  if (entry.isDir) return '📁'
  if (entry.kind === 'video') return '🎬'
  if (entry.kind === 'audio') return '🎵'
  return '📄'
}
</script>

<style scoped>
.browse {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

/* Drop target: a drop is addressed to the folder, so the whole list answers.
   `pointer-events: none` keeps it from eating the drag events being counted. */
.drop-veil {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  pointer-events: none;
  background: color-mix(in srgb, var(--bg) 74%, transparent);
  backdrop-filter: blur(2px);
}

.drop-card {
  display: grid;
  justify-items: center;
  gap: 4px;
  padding: 22px 30px;
  border: 1px dashed var(--accent);
  border-radius: var(--radius);
  background: var(--panel);
  text-align: center;
  box-shadow: var(--shadow);
}

.drop-card svg {
  width: 26px;
  height: 26px;
  color: var(--accent);
}

.drop-card p {
  margin: 2px 0 0;
  font-size: 13px;
  color: var(--text);
}

.drop-card span {
  font-size: 11.5px;
  color: var(--muted);
}

.centre {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--muted);
  font-size: 12.5px;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--line);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 24px;
  border-bottom: 1px solid var(--line-soft);
  flex: 0 0 auto;
}

.crumbs {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
  overflow: hidden;
}

.crumb {
  padding: 4px 7px;
  font-size: 12.5px;
  color: var(--text-dim);
  border-radius: 6px;
  white-space: nowrap;
}

.crumb:hover:not(:disabled) {
  background: var(--panel);
  color: var(--text);
}

.crumb:disabled {
  color: var(--text);
  opacity: 1;
  cursor: default;
  font-weight: 600;
}

.sep {
  color: var(--line);
  font-size: 11px;
}

.tools {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
}

.count {
  font-size: 11.5px;
  color: var(--muted);
}

/* The picking toggle stays lit while the mode is on, so a click that no longer
   opens files has a visible reason. Direct child only: the view switch keeps
   its own segmented styling. */
.tools > .on {
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
  color: var(--accent-strong);
}

/* Upload progress sits in the toolbar rather than in a panel: a batch is
   background work, and a folder being browsed is not blocked by it. */
.upload-chip {
  padding: 3px 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--panel);
  color: var(--text-dim);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.sm {
  padding: 5px 10px;
  font-size: 12px;
}

.sel-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 10px 24px 0;
  padding: 7px 12px;
  background: var(--accent-soft);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: var(--radius-sm);
  flex: 0 0 auto;
}

.sel-count {
  font-size: 12px;
  color: var(--text);
  font-variant-numeric: tabular-nums;
}

.sel-actions {
  display: flex;
  gap: 8px;
}

.segmented {
  display: flex;
  border: 1px solid var(--line);
  border-radius: 7px;
  overflow: hidden;
}

.segmented button {
  padding: 5px 10px;
  font-size: 12px;
  color: var(--muted);
  border-radius: 0;
}

.segmented button.on {
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.inline-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 24px 10px;
  padding: 8px 12px;
  border: 1px solid color-mix(in srgb, var(--danger) 40%, transparent);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--danger) 8%, transparent);
  color: var(--danger);
  font-size: 12px;
}

.body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 24px 28px;
  display: flex;
  flex-direction: column;
}

/* Same tile metrics as the web grid: a 150 px floor so thumbnails stay legible
   at any window width, and a tight gap that belongs to the artwork, not the row. */
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
  align-content: start;
}

.tile {
  position: relative;
  display: flex;
  flex-direction: column;
  /* Chromium's UA stylesheet centres a <button>'s contents, so flex items never
     stretch. The cover box now has only absolutely positioned children and thus
     no content width — without this its width (and the square) collapses to 0. */
  align-items: stretch;
  gap: 8px;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: var(--radius);
  text-align: left;
  transition: background 0.15s ease, border-color 0.15s ease, transform 0.18s ease,
    box-shadow 0.18s ease;
}

.tile:hover {
  background: var(--panel);
  border-color: var(--line);
  transform: translateY(-2px);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.18);
}

/* A marked tile reads through its frame: the artwork already fills the box, so
   a tinted background alone would be invisible in the grid. */
.tile.selected {
  background: var(--accent-soft);
  border-color: var(--accent);
}

.tile.selected:hover {
  border-color: var(--accent);
}

.art {
  position: relative;
  /* Definite width + aspect ratio: the glyph and the <img> are both absolutely
     positioned, so the box has no in-flow content to derive a size from. */
  width: 100%;
  aspect-ratio: 1;
  border-radius: 8px;
  background: var(--bg-elevated);
  border: 1px solid var(--line-soft);
  overflow: hidden;
}

/* Placeholder layer: the type glyph stays visible until the real thumbnail has
   decoded, so a slow folder never shows white rectangles. */
.art .glyph {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: 26px;
  opacity: 0.55;
}

.art.pending .glyph {
  animation: placeholder-breathe 1.5s ease-in-out infinite;
}

@keyframes placeholder-breathe {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 0.7;
  }
}

.art img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.art img.ready {
  opacity: 1;
}

/* Marks sit on the artwork, anchored to the corners so the picture stays the
   subject. The download affordance owns the top-right corner. */
.art .badge {
  position: absolute;
  pointer-events: none;
}

.lock-badge {
  top: 6px;
  right: 6px;
  padding: 3px 5px;
  font-size: 12px;
  line-height: 1;
}

.size-badge {
  right: 6px;
  bottom: 6px;
}

/* Play ring: video tiles read as video before anything is decoded. */
.play-badge {
  position: absolute;
  top: 50%;
  left: 50%;
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  padding-left: 3px;
  font-size: 13px;
  color: #fff;
  background: rgba(0, 0, 0, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.35);
  border-radius: 50%;
  backdrop-filter: blur(4px);
  transform: translate(-50%, -50%);
  pointer-events: none;
}

.glyph {
  font-size: 26px;
  opacity: 0.8;
}

.glyph.small {
  font-size: 15px;
  width: 20px;
  text-align: center;
}

/* Two lines of name, like the web tile: the artwork carries the metadata, so
   the name never has to shrink to a single unreadable line. */
.label {
  font-size: 12.5px;
  line-height: 1.35;
  color: var(--text);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-all;
}

.rows {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.row-item {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  text-align: left;
  width: 100%;
  transition: background 0.14s ease, box-shadow 0.16s ease;
}

.row-item:hover {
  background: var(--panel);
}

/* Marked rows keep a left rail so a selection stays readable even in the
   light theme, where the tint alone is very faint. */
.row-item.selected,
.row-item.selected:hover {
  background: var(--accent-soft);
  box-shadow: inset 2px 0 0 var(--accent);
}

/* Picking mode adds a checkbox: in flow for rows, pinned to the artwork for
   grid tiles. It is drawn empty until marked, so nothing shifts on toggle. */
.check {
  display: grid;
  place-items: center;
  width: 15px;
  height: 15px;
  flex: 0 0 15px;
  border: 1px solid var(--line-strong);
  border-radius: 4px;
  font-size: 10px;
  line-height: 1;
  color: transparent;
}

.art .check {
  position: absolute;
  top: 6px;
  left: 6px;
  z-index: 1;
  background: rgba(0, 0, 0, 0.4);
  border-color: rgba(255, 255, 255, 0.55);
}

.selected .check {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.row-item .name {
  flex: 1;
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-item .size {
  font-size: 11.5px;
  color: var(--muted);
  width: 76px;
  text-align: right;
  flex: 0 0 76px;
}

</style>
