<template>
  <div class="local">
    <!-- Locked: the local password is independent of every cloud profile. -->
    <div v-if="!local.unlocked" class="gate">
      <form class="gate-card" @submit.prevent="unlock">
        <h2>{{ $t('local.unlockTitle') }}</h2>
        <p>{{ $t('local.unlockDesc') }}</p>
        <input
          v-model="password"
          type="password"
          autocomplete="off"
          :placeholder="$t('local.passwordPlaceholder')"
          :disabled="busy"
        />
        <button class="primary" type="submit" :disabled="busy || !password">
          {{ busy ? $t('local.verifying') : $t('local.unlock') }}
        </button>
      </form>
    </div>

    <template v-else>
      <div class="toolbar">
        <nav v-if="local.root" class="crumbs">
          <template v-for="(crumb, index) in localCrumbs()" :key="crumb.path">
            <span v-if="index" class="sep">/</span>
            <button
              class="crumb"
              type="button"
              :disabled="index === localCrumbs().length - 1"
              @click="loadLocal(crumb.path)"
            >
              {{ crumb.label }}
            </button>
          </template>
        </nav>
        <span v-else class="muted">{{ $t('local.noRoot') }}</span>

        <div class="tools">
          <button
            class="ghost sm"
            type="button"
            :class="{ on: selectMode }"
            :disabled="!local.entries.length && !selectMode"
            @click="toggleSelectMode"
          >
            {{ selectMode ? $t('common.done') : $t('files.selectMode') }}
          </button>
          <button class="ghost sm" type="button" @click="pick">{{ $t('local.pickDir') }}</button>
          <button class="ghost sm" type="button" :disabled="!local.root" @click="loadLocal(local.path)">
            {{ $t('files.refresh') }}
          </button>
          <button class="ghost sm" type="button" @click="lock">{{ $t('local.lock') }}</button>
        </div>
      </div>

      <div v-if="selectMode" class="sel-bar">
        <span class="sel-count">{{ $t('files.selectedCount', { count: selected.size }) }}</span>
        <div class="sel-actions">
          <button class="ghost sm" type="button" @click="selectAll">{{ $t('files.selectAll') }}</button>
          <button
            class="ghost sm"
            type="button"
            :disabled="!selected.size"
            @click="clearSelection"
          >
            {{ $t('files.clearSelection') }}
          </button>
          <button
            class="ghost sm"
            type="button"
            :disabled="!downloadableSelected.length"
            @click="downloadSelected"
          >
            {{ $t('files.download') }}
          </button>
          <button
            class="danger sm"
            type="button"
            :disabled="!selected.size"
            @click="openDeleteSelected"
          >
            {{ $t('common.delete') }}
          </button>
        </div>
      </div>

      <div class="body" @click.self="clearSelection" @contextmenu.prevent="openMenu($event, null)">
        <EmptyState
          v-if="!local.root"
          icon="📁"
          :title="$t('local.emptyRootTitle')"
          :description="$t('local.emptyRootDesc')"
          :action-text="$t('local.emptyRootAction')"
          @action="pick"
        />

        <div v-else-if="local.loading && !local.entries.length" class="centre"><span class="spinner" /></div>

        <EmptyState
          v-else-if="local.error"
          icon="⚠"
          :title="$t('local.readFailed')"
          :description="local.error"
          :action-text="$t('common.retry')"
          @action="loadLocal(local.path)"
        />

        <EmptyState
          v-else-if="!local.entries.length"
          icon="🗂"
          :title="$t('files.emptyDirTitle')"
          :description="$t('local.emptyDirDesc')"
        />

        <div v-else class="rows" @click.self="clearSelection">
          <button
            v-for="(entry, i) in local.entries"
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
            <span class="glyph">
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
        :title="$t('files.mkdirTitle')"
        :description="$t('local.mkdirDesc')"
        :placeholder="$t('files.folderNamePlaceholder')"
        :confirm-text="$t('files.create')"
        :checkbox-label="$t('files.encryptName')"
        :checked-by-default="true"
        :busy="mkdir.busy"
        :error="mkdir.error"
        @cancel="mkdir.show = false"
        @submit="submitMkdir"
      />

      <PromptDialog
        :visible="rename.show"
        :title="rename.target?.isDir ? $t('files.renameFolder') : $t('files.rename')"
        :description="$t('files.renameDesc')"
        :placeholder="$t('files.newNamePlaceholder')"
        :initial-value="rename.target?.name || ''"
        :confirm-text="$t('files.rename')"
        :busy="rename.busy"
        :error="rename.error"
        @cancel="rename.show = false"
        @submit="submitRename"
      />

      <ConfirmDialog
        :visible="removal.show"
        :title="$t('files.deleteTitle')"
        :description="removalDescription"
        :busy="removal.busy"
        :error="removal.error"
        @cancel="removal.show = false"
        @confirm="submitDelete"
      />
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import ContextMenu from '../components/ContextMenu.vue'
import EmptyState from '../components/EmptyState.vue'
import IconImage from '../components/IconImage.vue'
import PromptDialog from '../components/PromptDialog.vue'
import {
  copyable,
  copyEntryLink,
  listExternalPlayers,
  openEntryExternally,
  playerMenuItems,
} from '../state/media-link.js'
import { toast } from '../state/session.js'
import {
  chooseRoot,
  formatSize,
  loadLocal,
  local,
  localCrumbs,
  lockLocal,
  makeFolder,
  refreshLocalStatus,
  removeEntries,
  renameEntry,
  setLocalRoot,
  unlockLocal,
} from '../state/local.js'
import { playEntry } from '../state/player.js'
import { enqueueDownload } from '../state/downloads.js'
import { t } from '../i18n.js'

const route = useRoute()
const router = useRouter()
const password = ref('')
const busy = ref(false)
/** Guards the association hand-off so unlock + watch cannot play twice. */
let openedFromQuery = false

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  await refreshLocalStatus()
  await openFromQuery()
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

// A `.ske` opened from the OS may arrive before the library is unlocked; the
// pending file must wait for the user rather than being dropped.
watch(() => local.unlocked, openFromQuery)

async function openFromQuery() {
  if (openedFromQuery) return

  const root = String(route.query.root || '')
  const file = String(route.query.file || '')
  if (!root || !file || !local.unlocked) return

  openedFromQuery = true
  try {
    if (local.root !== root) await setLocalRoot(root)
    else await loadLocal('/')

    const entry = local.entries.find((item) => !item.isDir && item.name === file)
    if (entry) activate(entry)
    else toast(t('local.notFound', { file }), 'error')
  } catch (err) {
    toast(err.message, 'error')
  } finally {
    router.replace({ path: '/local' })
  }
}

async function unlock() {
  busy.value = true
  try {
    await unlockLocal(password.value)
    password.value = ''
    toast(t('local.unlocked'), 'ok')
  } catch (err) {
    toast(err.message, 'error')
  } finally {
    busy.value = false
  }
}

async function pick() {
  try {
    if (!local.unlocked) {
      toast(t('local.needUnlock'), 'error')
      return
    }
    await chooseRoot()
  } catch (err) {
    toast(err.message, 'error')
  }
}

async function lock() {
  await lockLocal()
  toast(t('local.lockedToast'))
}

// ── Selection ──
// Picking is an explicit mode: the toolbar button turns row clicks into marks
// instead of opens, so nothing depends on remembering a modifier key. Shift
// still extends the range from the last row touched.
const selectMode = ref(false)
const selected = reactive(new Set())
const anchor = ref('')

const selectedEntries = computed(() =>
  local.entries.filter((entry) => selected.has(entry.path)),
)
const downloadableSelected = computed(() => selectedEntries.value.filter(isDownloadable))

// Navigating or locking ends the gesture: the marks belong to the folder and
// session they were made in.
watch(() => local.path, exitSelectMode)
watch(
  () => local.unlocked,
  (value) => {
    if (!value) exitSelectMode()
  },
)

// A reload can drop entries that are still marked, so the set is pruned to what
// the listing actually returned instead of leaking stale keys.
watch(
  () => local.entries,
  () => {
    const alive = new Set(local.entries.map((entry) => entry.path))
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
  for (const entry of local.entries) selected.add(entry.path)
  if (local.entries.length) anchor.value = local.entries[0].path
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
  const entries = local.entries
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
  if (selectAllPressed && local.entries.length) {
    event.preventDefault()
    selectMode.value = true
    selectAll()
  }
}

function activate(entry) {
  if (entry.isDir) {
    loadLocal(entry.path)
    return
  }
  if (entry.locked) {
    toast(t('local.decryptFailed'), 'error')
    return
  }
  if (!PLAYABLE_KINDS.has(entry.kind)) {
    toast(t('local.unsupported'), 'error')
    return
  }

  // The mini-playlist is the current folder filtered to the same media kind, so
  // an album of songs or a season of episodes plays through without re-picking.
  // Plain files are included: the media registry passes them through as-is.
  const playlist = local.entries
    .filter((item) => playableEntry(item) && item.kind === entry.kind)
    .map((item) => ({ mode: 'local', path: item.path, name: item.name, kind: item.kind }))
  const index = playlist.findIndex((item) => item.path === entry.path)

  const item = { mode: 'local', path: entry.path, name: entry.name, kind: entry.kind }

  playEntry(item, { playlist, index }).catch((err) => toast(err.message, 'error'))
}

async function download(entry) {
  try {
    await enqueueDownload({ mode: 'local', path: entry.path, name: entry.name })
    toast(t('files.queuedOne', { name: entry.name }), 'ok')
  } catch (err) {
    toast(err.message, 'error')
  }
}

/** Only a container that decrypted successfully has anything to write out. */
function isDownloadable(entry) {
  return !entry.isDir && entry.encrypted && !entry.locked
}

/** Queue a batch one by one: `enqueueDownload` reports failures per file. */
async function downloadMany(entries) {
  let added = 0
  let failure = null

  for (const entry of entries) {
    try {
      await enqueueDownload({ mode: 'local', path: entry.path, name: entry.name })
      added += 1
    } catch (err) {
      failure = failure || err
    }
  }

  if (added) toast(t('files.queuedMany', { count: added }), 'ok')
  if (failure) toast(failure.message, 'error')
}

function downloadSelected() {
  const files = downloadableSelected.value
  if (!files.length) {
    toast(t('local.nothingDownloadable'), 'error')
    return
  }
  downloadMany(files)
}

// ── Context menu ──
// Same gesture and option set as the cloud library, applied to the directory on
// disk: new folder on the background, open/download/rename/delete on an entry.
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
      [
        { label: t('files.mkdirTitle'), icon: '📁', action: openMkdir },
        { label: t('files.refresh'), icon: '🔄', action: () => loadLocal(local.path) },
      ],
    ])
  }

  // Right-clicking a marked row acts on the whole selection; anything else is a
  // single target. Renaming has no batch form, so it is single-entry only.
  const marked = selected.has(entry.path) && selected.size > 1
  const targets = marked ? [...selectedEntries.value] : [entry]

  if (targets.length > 1) {
    const files = targets.filter(isDownloadable)
    return grouped([
      [
        {
          label: t('files.downloadCount', { count: files.length }),
          icon: '⤓',
          disabled: !files.length,
          action: () => downloadMany(files),
        },
      ],
      [{ label: t('files.clearSelection'), icon: '✕', action: clearSelection }],
      [
        {
          label: t('files.deleteCount', { count: targets.length }),
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
  // Only containers are worth writing out again: a plain file already is one.
  if (!entry.isDir && entry.encrypted) {
    file.push({
      label: t('files.download'),
      icon: '⤓',
      disabled: entry.locked,
      action: () => download(entry),
    })
  }
  file.push({ label: t('files.rename'), icon: '✏️', action: () => openRename(entry) })

  const playback = []
  if (copyable(entry)) {
    playback.push(
      { label: t('files.copyLink'), icon: '🔗', disabled: entry.locked, action: () => copyLink(entry) },
      {
        label: t('files.externalPlayer'),
        icon: '🎬',
        disabled: entry.locked,
        // Second level: which player. Detection happens in the main process.
        children: playerMenuItems(players, (player) => playExternal(entry, player.path)),
      },
    )
  }

  const destroy = [{ label: t('common.delete'), icon: '🗑️', danger: true, action: () => openDelete(entry) }]

  return grouped([file, playback, destroy])
}

/**
 * Copy the URL of a decrypted stream.
 *
 * Only useful while the session behind it stays open: the main side keeps it
 * and closes the previous one, while quitting the app or copying another entry
 * ends it.
 */
async function copyLink(entry) {
  try {
    await copyEntryLink({ mode: 'local', path: entry.path, name: entry.name })
    toast(t('files.linkCopied'), 'ok')
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
      { mode: 'local', path: entry.path, name: entry.name },
      exe,
    )
    toast(t('files.openedWith', { player: res.player || t('files.externalPlayer') }), 'ok')
  } catch (err) {
    toast(err.message, 'error')
  }
}

async function openMenu(event, entry) {
  if (entry && selectMode.value) {
    // Right-click outside the marks targets that row alone; inside them the
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

// ── Dialogs ──
const mkdir = reactive({ show: false, busy: false, error: '' })
const rename = reactive({ show: false, target: null, busy: false, error: '' })
const removal = reactive({ show: false, targets: [], busy: false, error: '' })

const removalDescription = computed(() => {
  const targets = removal.targets
  if (!targets.length) return ''
  if (targets.length === 1) return t('local.removeOne', { name: targets[0].name })
  return t('local.removeMany', { count: targets.length })
})

function openMkdir() {
  mkdir.error = ''
  mkdir.show = true
}

async function submitMkdir({ text, checked }) {
  mkdir.busy = true
  mkdir.error = ''
  try {
    await makeFolder(local.path, text, checked)
    mkdir.show = false
    toast(t('files.created', { name: text }), 'ok')
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
    toast(t('files.renamed', { name: text }), 'ok')
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
      toast(t('files.removeFailed', { count: failed.length, error: failed[0].error }), 'error')
      return
    }
    toast(
      targets.length === 1
        ? t('files.removedOne', { name: targets[0].name })
        : t('files.removedMany', { count: targets.length }),
      'ok',
    )
  } catch (err) {
    removal.error = err.message
  } finally {
    removal.busy = false
  }
}

const PLAYABLE_KINDS = new Set(['video', 'audio', 'image'])

/** Containers and plain files both play; only broken containers are excluded. */
function playableEntry(entry) {
  return !entry.isDir && !entry.locked && PLAYABLE_KINDS.has(entry.kind)
}

/**
 * Every entry carries its state: a local folder can hold containers next to
 * files the user dropped in by hand, and only the containers need the password.
 */
function tagLabel(entry) {
  if (entry.locked) return t('browse.tagLocked')
  return entry.encrypted ? t('browse.tagEncrypted') : t('browse.tagPlain')
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
.local {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.gate {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 40px;
}

.gate-card {
  width: 380px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 26px 26px 22px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
}

.gate-card h2 {
  font-size: 15px;
}

.gate-card p {
  margin: 0 0 4px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
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

.muted {
  font-size: 12px;
  color: var(--muted);
}

.tools {
  display: flex;
  gap: 8px;
  flex: 0 0 auto;
}

/* The picking toggle stays lit while the mode is on, so a click that no longer
   opens files has a visible reason. */
.tools > .on {
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
  color: var(--accent-strong);
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

.body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 24px 28px;
  display: flex;
  flex-direction: column;
}

.centre {
  flex: 1;
  display: grid;
  place-items: center;
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

/* Picking mode adds a checkbox box in front of the glyph; it is drawn empty
   until marked, so the row height never changes when the mode flips. */
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

.row-item.selected .check {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.glyph {
  font-size: 15px;
  width: 20px;
  text-align: center;
}

.name {
  flex: 1;
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.size {
  font-size: 11.5px;
  color: var(--muted);
  width: 76px;
  text-align: right;
  flex: 0 0 76px;
}

</style>
