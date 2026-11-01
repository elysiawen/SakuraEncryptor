<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>{{ $t('settings.title') }}</h2>
        <p>{{ $t('settings.subtitle') }}</p>
      </div>
    </div>

    <section class="card">
      <h3>{{ $t('settings.appearance.title') }}</h3>
      <p class="hint">{{ $t('settings.appearance.hint') }}</p>

      <div class="switch-row">
        <div>
          <div class="label">{{ $t('settings.appearance.language') }}</div>
          <div class="desc">{{ $t('settings.appearance.languageDesc') }}</div>
        </div>
        <select class="lang" :value="language" @change="onLanguageChange">
          <option v-for="option in languageOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </div>
    </section>

    <section class="card">
      <h3>{{ $t('settings.lock.title') }}</h3>
      <p class="hint">{{ $t('settings.lock.hint') }}</p>

      <div class="status-line">
        <span class="badge" :class="{ ok: session.lock.enabled }">
          {{ session.lock.enabled ? $t('settings.lock.enabled') : $t('settings.lock.disabled') }}
        </span>
        <span v-if="session.lock.hasPassword" class="badge">{{ $t('settings.lock.passwordBadge') }}</span>
        <span v-if="session.lock.hasTotp" class="badge">TOTP</span>
      </div>

      <div class="switch-row">
        <div>
          <div class="label">{{ $t('settings.lock.password') }}</div>
          <div class="desc">
            {{ session.lock.hasPassword ? $t('settings.lock.passwordSet') : $t('settings.lock.notSet') }}
          </div>
        </div>
        <button class="ghost sm" type="button" @click="openLockDialog('password')">
          {{ session.lock.hasPassword ? $t('settings.lock.change') : $t('settings.lock.set') }}
        </button>
      </div>

      <div class="switch-row">
        <div>
          <div class="label">{{ $t('settings.lock.totp') }}</div>
          <div class="desc">
            {{ session.lock.hasTotp ? $t('settings.lock.totpBound') : $t('settings.lock.totpUnbound') }}
          </div>
        </div>
        <button class="ghost sm" type="button" @click="toggleTotp">
          {{ session.lock.hasTotp ? $t('settings.lock.unbind') : $t('settings.lock.bind') }}
        </button>
      </div>

      <div v-if="session.lock.hasPassword && session.lock.hasTotp" class="switch-row">
        <div>
          <div class="label">{{ $t('settings.lock.anyFactor') }}</div>
          <div class="desc">
            {{ session.lock.match === 'any' ? $t('settings.lock.matchAny') : $t('settings.lock.matchAll') }}
          </div>
        </div>
        <input
          type="checkbox"
          class="check"
          :checked="session.lock.match === 'any'"
          @change="(e) => updateMatch(e.target.checked ? 'any' : 'all')"
        />
      </div>

      <div v-if="session.lock.enabled" class="usage-foot">
        <span class="desc">{{ $t('settings.lock.disableHint') }}</span>
        <button class="danger" type="button" @click="openLockDialog('disable')">
          {{ $t('settings.lock.disable') }}
        </button>
      </div>
    </section>

    <LockDialog
      :visible="dialog.open"
      :mode="dialog.mode"
      :has-password="session.lock.hasPassword"
      :has-totp="session.lock.hasTotp"
      :min-password="MIN_LOCK_PASSWORD"
      :secret="dialog.secret"
      :uri="dialog.uri"
      :busy="dialog.busy"
      :error="dialog.error"
      @cancel="dialog.open = false"
      @regenerate="loadTotpProposal"
      @submit="submitLockDialog"
    />

    <section class="card">
      <h3>{{ $t('settings.embedded.title') }}</h3>
      <p class="hint">{{ $t('settings.embedded.hint') }}</p>

      <div class="switch-row">
        <div>
          <div class="label">{{ $t('settings.embedded.status') }}</div>
          <div class="desc">{{ embeddedLine }}</div>
        </div>
        <button
          v-if="embeddedState?.status !== 'running'"
          class="primary sm"
          type="button"
          :disabled="embeddedBusy"
          @click="toggleEmbedded"
        >
          {{
            embeddedBusy
              ? $t('common.processing')
              : embeddedState?.status === 'error'
                ? $t('common.retry')
                : $t('settings.embedded.enable')
          }}
        </button>
        <button v-else class="ghost sm" type="button" :disabled="embeddedBusy" @click="toggleEmbedded">
          {{ $t('common.close') }}
        </button>
      </div>

      <div class="row" style="margin-top: 12px">
        <button class="ghost sm" type="button" @click="embeddedDialog = 'backend'">
          {{ $t('settings.embedded.backend') }}
        </button>
        <button
          class="ghost sm"
          type="button"
          :disabled="embeddedState?.status !== 'running'"
          :title="embeddedState?.status !== 'running' ? $t('settings.embedded.startFirst') : ''"
          @click="embeddedDialog = 'account'"
        >
          {{ $t('settings.embedded.account') }}
        </button>
        <button
          class="ghost sm"
          type="button"
          :disabled="embeddedState?.status !== 'running'"
          :title="embeddedState?.status !== 'running' ? $t('settings.embedded.startFirst') : ''"
          @click="embeddedDialog = 'storage'"
        >
          {{ $t('settings.embedded.storage') }}
        </button>
      </div>
    </section>

    <EmbeddedBackendDialog :visible="embeddedDialog === 'backend'" @close="embeddedDialog = null" />
    <EmbeddedAccountDialog :visible="embeddedDialog === 'account'" @close="embeddedDialog = null" />
    <EmbeddedStorageDialog :visible="embeddedDialog === 'storage'" @close="embeddedDialog = null" />

    <section class="card">
      <h3>{{ $t('settings.cache.title') }}</h3>

      <div class="switch-row">
        <div>
          <div class="label">{{ $t('settings.cache.thumbTitle') }}</div>
          <div class="desc">{{ $t('settings.cache.thumbDesc') }}</div>
        </div>
        <input
          type="checkbox"
          class="check"
          :checked="cache.thumbEnabled"
          @change="(e) => updateCache({ thumbEnabled: e.target.checked })"
        />
      </div>

      <div class="switch-row">
        <div>
          <div class="label">{{ $t('settings.cache.limit') }}</div>
          <div class="desc">{{ $t('settings.cache.limitDesc') }}</div>
        </div>
        <div class="row">
          <input
            class="num"
            type="number"
            min="32"
            max="2048"
            step="32"
            :disabled="!cache.thumbEnabled"
            :value="cache.thumbLimitMB"
            @change="(e) => updateCache({ thumbLimitMB: clampNumber(e, 32, 2048) })"
          />
          <span class="unit">MB</span>
        </div>
      </div>

      <div class="usage">
        <div class="usage-row">
          <div>
            <div class="label">{{ $t('settings.cache.thumb') }}</div>
            <div class="desc">
              {{
                $t('settings.cache.thumbUsage', {
                  count: usage.previews,
                  bytes: bytesLabel(usage.previewBytes),
                  links: usage.urls,
                })
              }}
            </div>
            <div class="desc">
              {{ $t('settings.cache.thumbDisk', { count: usage.thumbDisk, bytes: bytesLabel(usage.thumbDiskBytes) }) }}
            </div>
          </div>
          <button class="ghost sm" type="button" @click="clearCache('previews')">
            {{ $t('settings.cache.clear') }}
          </button>
        </div>

        <div class="usage-row">
          <div>
            <div class="label">{{ $t('settings.cache.blocks') }}</div>
            <div class="desc">
              {{
                $t('settings.cache.blockUsage', {
                  count: usage.blocks,
                  bytes: bytesLabel(usage.blockBytes),
                  sessions: usage.sessions,
                })
              }}
            </div>
          </div>
          <button class="ghost sm" type="button" @click="clearCache('blocks')">
            {{ $t('settings.cache.clear') }}
          </button>
        </div>

        <div class="usage-row">
          <div>
            <div class="label">{{ $t('settings.cache.links') }}</div>
            <div class="desc">{{ $t('settings.cache.linkUsage', { count: usage.links }) }}</div>
          </div>
          <button class="ghost sm" type="button" @click="clearCache('links')">
            {{ $t('settings.cache.clear') }}
          </button>
        </div>
      </div>

      <div class="usage-foot">
        <span class="desc">{{ $t('settings.cache.footHint') }}</span>
        <div class="row">
          <button class="ghost sm" type="button" @click="clearCache('all')">
            {{ $t('settings.cache.clearAll') }}
          </button>
          <button class="ghost sm" type="button" :disabled="!usage.sessions" @click="clearCache('sessions')">
            {{ $t('settings.cache.clearSessions') }}
          </button>
        </div>
      </div>
    </section>

    <section class="card">
      <h3>{{ $t('settings.playback.title') }}</h3>
      <p class="hint">{{ $t('settings.playback.hint') }}</p>

      <div class="switch-row">
        <div>
          <div class="label">{{ $t('settings.playback.seekStep') }}</div>
          <div class="desc">{{ $t('settings.playback.seekStepDesc') }}</div>
        </div>
        <div class="row">
          <input
            class="num"
            type="number"
            min="1"
            max="60"
            :value="playback.seekStep"
            @change="(e) => updatePlayback({ seekStep: clampNumber(e, 1, 60) })"
          />
          <span class="unit">{{ $t('settings.playback.secondsUnit') }}</span>
        </div>
      </div>

      <div class="switch-row">
        <div>
          <div class="label">{{ $t('settings.playback.autoNext') }}</div>
          <div class="desc">{{ $t('settings.playback.autoNextDesc') }}</div>
        </div>
        <input
          type="checkbox"
          class="check"
          :checked="playback.autoPlayNext"
          @change="(e) => updatePlayback({ autoPlayNext: e.target.checked })"
        />
      </div>

      <div class="switch-row">
        <div>
          <div class="label">{{ $t('settings.playback.externalPlayer') }}</div>
          <div class="desc" :title="playback.externalPlayer || undefined">
            {{ playback.externalPlayer || $t('settings.playback.externalHint') }}
          </div>
        </div>
        <div class="row">
          <button class="ghost sm" type="button" @click="pickPlayer">
            {{ $t('settings.playback.choose') }}
          </button>
          <button
            v-if="playback.externalPlayer"
            class="ghost sm"
            type="button"
            @click="updatePlayback({ externalPlayer: '' })"
          >
            {{ $t('settings.playback.automatic') }}
          </button>
        </div>
      </div>
    </section>

    <section class="card">
      <h3>{{ $t('settings.download.title') }}</h3>
      <p class="hint">{{ $t('settings.download.hint') }}</p>

      <div class="switch-row">
        <div>
          <div class="label">{{ $t('settings.download.dir') }}</div>
          <div class="desc path mono" :title="download.dir || ''">
            {{ download.dir || $t('settings.download.dirEmpty') }}
          </div>
        </div>
        <button class="ghost sm" type="button" @click="pickDownloadDir">
          {{ $t('settings.download.pickDir') }}
        </button>
      </div>
    </section>

    <section class="card">
      <h3>{{ $t('settings.window.title') }}</h3>
      <p class="hint">{{ $t('settings.window.hint') }}</p>

      <div class="switch-row">
        <div>
          <div class="label">{{ $t('settings.window.closeToTray') }}</div>
          <div class="desc">{{ $t('settings.window.closeToTrayDesc') }}</div>
        </div>
        <input
          type="checkbox"
          class="check"
          :checked="winPrefs.closeToTray"
          @change="(e) => updateWindow({ closeToTray: e.target.checked })"
        />
      </div>
    </section>

    <section class="card">
      <h3>{{ $t('settings.about.title') }}</h3>
      <div class="about">
        <div class="kv">
          <span>{{ $t('settings.about.version') }}</span>
          <span class="mono">{{ session.info?.version || '—' }}</span>
        </div>
        <div class="kv">
          <span>{{ $t('settings.about.platform') }}</span>
          <span class="mono">{{ session.info?.platform || '—' }}</span>
        </div>
        <div class="kv">
          <span>{{ $t('settings.about.dataDir') }}</span>
          <span class="mono path" :title="session.info?.userData">{{ session.info?.userData || '—' }}</span>
        </div>
      </div>
      <div class="row" style="margin-top: 14px">
        <RouterLink to="/diagnostics">
          <button class="ghost" type="button">{{ $t('nav.diagnostics') }}</button>
        </RouterLink>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import {
  attempt,
  beginTotp,
  confirmTotp,
  disableLock,
  patchSettings,
  session,
  setLockMatch,
  setLockPassword,
  toast,
  unbindTotp,
} from '../state/session.js'
import { resetThumbnailCache, thumbnailStats } from '../state/thumbnails.js'
import {
  bindEmbedded,
  disableEmbedded,
  embedded,
  enableEmbedded,
  loadEmbedded,
  statusDetail,
} from '../state/embedded.js'
import LockDialog from '../components/LockDialog.vue'
import EmbeddedBackendDialog from '../components/EmbeddedBackendDialog.vue'
import EmbeddedAccountDialog from '../components/EmbeddedAccountDialog.vue'
import EmbeddedStorageDialog from '../components/EmbeddedStorageDialog.vue'
import { LANGUAGE_AUTO, LOCALES } from '../../shared/i18n/config.js'
import { languageChoice, setLocale, t } from '../i18n.js'

/**
 * The dropdown shows the user's *choice* — `auto` included — not the resolved
 * locale. Someone who picked "follow system" should keep seeing that, rather
 * than "简体中文" on a Chinese machine and "English" on the next one.
 */
const language = computed(() => languageChoice())

const languageOptions = computed(() => [
  { value: LANGUAGE_AUTO, label: t('settings.appearance.followSystem') },
  ...LOCALES.map((entry) => ({ value: entry.code, label: entry.label })),
])

async function onLanguageChange(event) {
  await setLocale(event.target.value)
}

/**
 * Mirrors `MIN_PASSWORD_LENGTH` in `src/main/security.js`.
 *
 * The main process stays the authority; this copy only keeps the button from
 * inviting a submission that is guaranteed to be rejected.
 */
const MIN_LOCK_PASSWORD = 6

/** Password inputs live in `LockDialog`; the card only shows state and actions. */

/**
 * Live usage of the caches.
 *
 * The renderer contributes the preview-URL count (that cache is not visible to
 * the main process); everything else is measured where it lives. `thumbDisk`
 * is the encrypted on-disk half of the preview cache.
 */
const usage = reactive({
  previews: 0,
  previewBytes: 0,
  thumbDisk: 0,
  thumbDiskBytes: 0,
  links: 0,
  sessions: 0,
  blocks: 0,
  blockBytes: 0,
  urls: 0,
})

async function refreshUsage() {
  try {
    const res = await window.ske.cache.stats()
    if (res?.ok) {
      usage.previews = res.previews || 0
      usage.previewBytes = res.previewBytes || 0
      usage.thumbDisk = res.thumbDisk || 0
      usage.thumbDiskBytes = res.thumbDiskBytes || 0
      usage.links = res.links || 0
      usage.sessions = res.sessions || 0
      usage.blocks = res.blocks || 0
      usage.blockBytes = res.blockBytes || 0
    }
  } catch {
    // These numbers are cosmetic: a cache probe must never break the settings.
  }
  usage.urls = thumbnailStats().urls
}

async function clearCache(target) {
  // Preview URLs (and the decrypt sessions behind them) live in the renderer.
  // Clearing main-side state without them would leave tiles pointing at
  // sessions that no longer answer.
  if (target === 'previews' || target === 'sessions' || target === 'all') {
    resetThumbnailCache()
  }

  const res = await window.ske.cache.clear(target)
  await refreshUsage()
  toast(res?.ok ? t('settings.cache.cleared') : res?.error || t('settings.cache.clearFailed'), res?.ok ? 'ok' : 'error')
}

function bytesLabel(bytes) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(unit > 0 && value < 10 ? 1 : 0)} ${units[unit]}`
}

// Poll while the page is open: a download or a playback session changes these
// numbers without anything in the section re-rendering.
let usageTimer = null
let stopEmbedded = () => {}
onMounted(() => {
  refreshUsage()
  usageTimer = setInterval(refreshUsage, 2000)
  // The card has to reflect a download started from another page, so it follows
  // the same push channel as the dedicated page.
  loadEmbedded()
  stopEmbedded = bindEmbedded()
})
onUnmounted(() => {
  clearInterval(usageTimer)
  stopEmbedded()
})

/**
 * Only the on-disk preview cache is configurable.
 *
 * The decrypted blocks never leave memory, so a switch there would promise a
 * privacy choice that does not exist; their bound is fixed instead.
 */
const cache = computed(() => ({
  thumbEnabled: session.settings?.cache?.thumbEnabled ?? true,
  thumbLimitMB: session.settings?.cache?.thumbLimitMB ?? 128,
}))

const playback = computed(() => ({
  seekStep: session.settings?.playback?.seekStep ?? 5,
  autoPlayNext: session.settings?.playback?.autoPlayNext ?? true,
  externalPlayer: session.settings?.playback?.externalPlayer ?? '',
}))

const download = computed(() => ({
  dir: session.settings?.download?.dir ?? '',
}))

const winPrefs = computed(() => ({
  closeToTray: session.settings?.window?.closeToTray ?? false,
}))

const embeddedState = computed(() => embedded.state)
const embeddedBusy = computed(() => embedded.busy)

/** Which embedded-server dialog is open: 'backend' | 'account' | 'storage' | null. */
const embeddedDialog = ref(null)

/** One line is all the settings page says; details live on the dedicated page. */
/**
 * One sentence about the server, from the module that owns its state.
 *
 * The card used to compute this itself, which meant the same eight states were
 * worded twice — here and in `statusDetail` — and drifted apart.
 */
const embeddedLine = computed(() => statusDetail(embedded.state))

async function toggleEmbedded() {
  if (embedded.state?.status === 'running') await disableEmbedded()
  else await enableEmbedded()
}

function clampNumber(event, min, max) {
  const value = Number(event.target.value)
  if (!Number.isFinite(value)) return min
  return Math.min(max, Math.max(min, Math.round(value)))
}

/**
 * Persist a cache setting.
 *
 * The write returns only after the main process has applied it — deleting the
 * directory when the switch goes off, trimming it when the limit shrinks — so
 * re-reading the usage right afterwards shows the real size.
 */
async function updateCache(patch) {
  const saved = await attempt(() => patchSettings({ cache: patch }))
  if (saved) await refreshUsage()
}

function updatePlayback(patch) {
  attempt(() => patchSettings({ playback: patch }))
}

/** Pin the player the "外部播放器" command should launch; empty means auto-detect. */
async function pickPlayer() {
  const res = await window.ske.dialog.pickPlayer()
  if (!res?.ok || !res.path) return
  updatePlayback({ externalPlayer: res.path })
}

function updateWindow(patch) {
  attempt(() => patchSettings({ window: patch }))
}

async function pickDownloadDir() {
  const picked = await window.ske.dialog.pickDirectory()
  if (!picked?.ok || !picked.path) return
  attempt(() => patchSettings({ download: { dir: picked.path } }), {
    success: t('settings.download.dirUpdated'),
  })
}

/**
 * The launch-gate dialog.
 *
 * `secret`/`uri` only matter in the `totp` mode: the main process proposes
 * them, and nothing is stored until a code generated from that secret comes
 * back. Errors stay in the dialog rather than a toast — the user is standing in
 * the form and can correct the input right there.
 */
const dialog = reactive({ open: false, mode: 'password', secret: '', uri: '', busy: false, error: '' })

async function openLockDialog(mode) {
  dialog.mode = mode
  dialog.error = ''
  dialog.secret = ''
  dialog.uri = ''
  dialog.open = true
  if (mode === 'totp') await loadTotpProposal()
}

async function loadTotpProposal() {
  try {
    const created = await beginTotp()
    dialog.secret = created.secret
    dialog.uri = created.uri
    dialog.error = ''
  } catch (err) {
    dialog.error = err.message
  }
}

async function toggleTotp() {
  if (!session.lock.hasTotp) {
    await openLockDialog('totp')
    return
  }
  const ok = await attempt(() => unbindTotp(), { success: t('settings.lock.totpUnboundToast') })
  if (ok !== null) dialog.open = false
}

async function updateMatch(match) {
  await attempt(() => setLockMatch(match))
}

async function submitLockDialog(payload) {
  dialog.busy = true
  dialog.error = ''
  const hadPassword = session.lock.hasPassword
  try {
    if (dialog.mode === 'password') {
      await setLockPassword(payload.password, payload.currentPassword)
      toast(hadPassword ? t('settings.lock.passwordUpdated') : t('settings.lock.passwordEnabled'), 'ok')
    } else if (dialog.mode === 'totp') {
      await confirmTotp(payload.code, payload.secret)
      toast(t('settings.lock.totpBoundToast'), 'ok')
    } else {
      await disableLock({ password: payload.password, totp: payload.totp })
      toast(t('settings.lock.disabledToast'), 'ok')
    }
    dialog.open = false
  } catch (err) {
    dialog.error = err.message
  } finally {
    dialog.busy = false
  }
}
</script>

<style scoped>
/* Fields are `width: 100%` globally, which would starve the label beside them. */
.lang {
  width: auto;
  min-width: 148px;
  flex: 0 0 auto;
  padding: 6px 9px;
  cursor: pointer;
}

.status-line {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

/* Live cache usage: same rhythm as the switch rows above, with the numbers
   sitting where the matching clear button is. */
.usage {
  margin-top: 4px;
  border-top: 1px solid var(--line-soft);
}

.usage-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 11px 0;
  border-bottom: 1px solid var(--line-soft);
}

.usage-row .label {
  font-size: 13px;
}

.usage-row .desc {
  font-size: 11px;
  color: var(--muted);
  margin-top: 2px;
}

.usage-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-top: 12px;
}

.usage-foot .desc {
  font-size: 11px;
  color: var(--muted);
}

.usage-foot .row {
  gap: 8px;
  flex: 0 0 auto;
}

.num {
  width: 76px;
  text-align: right;
  padding: 6px 8px;
}

.sm {
  padding: 5px 10px;
  font-size: 12px;
  flex: 0 0 auto;
}

.unit {
  font-size: 11.5px;
  color: var(--muted);
  width: 22px;
}

.check {
  width: 16px;
  height: 16px;
  margin-left: 4px;
  accent-color: var(--accent);
  cursor: pointer;
}

.about {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 6px;
}

.kv {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--text-dim);
}

.kv > span:first-child {
  width: 70px;
  color: var(--muted);
  flex: 0 0 70px;
}

.path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
