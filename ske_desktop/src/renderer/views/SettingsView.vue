<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>设置</h2>
        <p>所有设置保存在本机，不随账号同步。</p>
      </div>
    </div>

    <section class="card">
      <h3>启动锁</h3>
      <p class="hint">
        启动应用时先通过验证，可防止他人直接打开你的媒体库。密码与 TOTP 验证器可以单独使用，也可以组合：两个都启用时可选「任一通过」或「都通过」。这是应用级锁，不加密磁盘上的文件。
      </p>

      <div class="status-line">
        <span class="badge" :class="{ ok: session.lock.enabled }">
          {{ session.lock.enabled ? '已启用' : '未启用' }}
        </span>
        <span v-if="session.lock.hasPassword" class="badge">密码</span>
        <span v-if="session.lock.hasTotp" class="badge">TOTP</span>
      </div>

      <div class="switch-row">
        <div>
          <div class="label">启动密码</div>
          <div class="desc">{{ session.lock.hasPassword ? '启动时需要输入密码' : '尚未设置' }}</div>
        </div>
        <button class="ghost sm" type="button" @click="openLockDialog('password')">
          {{ session.lock.hasPassword ? '修改' : '设置' }}
        </button>
      </div>

      <div class="switch-row">
        <div>
          <div class="label">TOTP 身份验证器</div>
          <div class="desc">
            {{
              session.lock.hasTotp
                ? '已绑定，启动时可要求输入 6 位验证码'
                : '绑定后可用验证器应用中的 6 位验证码解锁'
            }}
          </div>
        </div>
        <button class="ghost sm" type="button" @click="toggleTotp">
          {{ session.lock.hasTotp ? '解绑' : '绑定' }}
        </button>
      </div>

      <div v-if="session.lock.hasPassword && session.lock.hasTotp" class="switch-row">
        <div>
          <div class="label">任一方式通过即可</div>
          <div class="desc">
            {{
              session.lock.match === 'any'
                ? '密码或验证码任一通过就能进入'
                : '密码和验证码必须都通过'
            }}
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
        <span class="desc">关闭后启动不再验证，已绑定的 TOTP 密钥一并清除。</span>
        <button class="danger" type="button" @click="openLockDialog('disable')">关闭启动锁</button>
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
      <h3>内置服务器</h3>
      <p class="hint">
        在本机直接跑一个 AList 或 OpenList：文件留在这台电脑，不需要外部服务器或账号。
        首次开启会从 GitHub 下载对应平台的程序包，之后都从本机启动；服务器只监听 127.0.0.1，局域网看不到它。
      </p>

      <div class="switch-row">
        <div>
          <div class="label">运行状态</div>
          <div class="desc">{{ embeddedLine }}</div>
        </div>
        <button
          v-if="embeddedState?.status !== 'running'"
          class="primary sm"
          type="button"
          :disabled="embeddedBusy"
          @click="toggleEmbedded"
        >
          {{ embeddedBusy ? '处理中…' : embeddedState?.status === 'error' ? '重试' : '开启' }}
        </button>
        <button v-else class="ghost sm" type="button" :disabled="embeddedBusy" @click="toggleEmbedded">
          关闭
        </button>
      </div>

      <div class="row" style="margin-top: 12px">
        <button class="ghost sm" type="button" @click="embeddedDialog = 'backend'">后端管理</button>
        <button
          class="ghost sm"
          type="button"
          :disabled="embeddedState?.status !== 'running'"
          :title="embeddedState?.status !== 'running' ? '先开启服务器' : ''"
          @click="embeddedDialog = 'account'"
        >
          账号密码
        </button>
        <button
          class="ghost sm"
          type="button"
          :disabled="embeddedState?.status !== 'running'"
          :title="embeddedState?.status !== 'running' ? '先开启服务器' : ''"
          @click="embeddedDialog = 'storage'"
        >
          存储管理
        </button>
      </div>
    </section>

    <EmbeddedBackendDialog :visible="embeddedDialog === 'backend'" @close="embeddedDialog = null" />
    <EmbeddedAccountDialog :visible="embeddedDialog === 'account'" @close="embeddedDialog = null" />
    <EmbeddedStorageDialog :visible="embeddedDialog === 'storage'" @close="embeddedDialog = null" />

    <section class="card">
      <h3>缓存</h3>

      <div class="switch-row">
        <div>
          <div class="label">缩略图缓存到磁盘</div>
          <div class="desc">
            关闭后立即删除已写入的加密缓存；只是下次启动要重新拉取并解密预览
          </div>
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
          <div class="label">磁盘上限</div>
          <div class="desc">超出后按最久未用淘汰，调小时立刻裁剪</div>
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
            <div class="label">缩略图</div>
            <div class="desc">
              {{ usage.previews }} 张已解密 · {{ bytesLabel(usage.previewBytes) }} ·
              {{ usage.urls }} 个预览地址
            </div>
            <div class="desc">
              磁盘：{{ usage.thumbDisk }} 张加密缓存 · {{ bytesLabel(usage.thumbDiskBytes) }}
            </div>
          </div>
          <button class="ghost sm" type="button" @click="clearCache('previews')">清除</button>
        </div>

        <div class="usage-row">
          <div>
            <div class="label">解密块</div>
            <div class="desc">
              {{ usage.blocks }} 块 · {{ bytesLabel(usage.blockBytes) }} · {{ usage.sessions }} 个会话
            </div>
          </div>
          <button class="ghost sm" type="button" @click="clearCache('blocks')">清除</button>
        </div>

        <div class="usage-row">
          <div>
            <div class="label">下载地址</div>
            <div class="desc">{{ usage.links }} 条签名链接，5 分钟后自动过期</div>
          </div>
          <button class="ghost sm" type="button" @click="clearCache('links')">清除</button>
        </div>
      </div>

      <div class="usage-foot">
        <span class="desc">全部清除不会打断播放；关闭会话会中断正在播放的内容。</span>
        <div class="row">
          <button class="ghost sm" type="button" @click="clearCache('all')">全部清除</button>
          <button class="ghost sm" type="button" :disabled="!usage.sessions" @click="clearCache('sessions')">
            关闭全部会话
          </button>
        </div>
      </div>
    </section>

    <section class="card">
      <h3>播放</h3>
      <p class="hint">
        内置播放器覆盖 mp4 / webm 等常见容器；avi、wmv、flv、ts 与老编码可以交给外部播放器，直接读同一条解密流。
      </p>

      <div class="switch-row">
        <div>
          <div class="label">方向键跳转步长</div>
          <div class="desc">播放时按左右方向键前进或后退的秒数</div>
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
          <span class="unit">秒</span>
        </div>
      </div>

      <div class="switch-row">
        <div>
          <div class="label">自动播放下一首</div>
          <div class="desc">当前曲目结束后继续播放同一目录的下一项</div>
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
          <div class="label">外部播放器</div>
          <div class="desc" :title="playback.externalPlayer || undefined">
            {{ playback.externalPlayer || '留空则自动寻找 mpv / VLC / PotPlayer；右键文件的「外部播放器」里还能临时换一个' }}
          </div>
        </div>
        <div class="row">
          <button class="ghost sm" type="button" @click="pickPlayer">选择</button>
          <button
            v-if="playback.externalPlayer"
            class="ghost sm"
            type="button"
            @click="updatePlayback({ externalPlayer: '' })"
          >
            自动
          </button>
        </div>
      </div>
    </section>

    <section class="card">
      <h3>下载</h3>
      <p class="hint">
        下载会把 .ske 容器解密成普通文件写到本机。明文一旦离开应用就不再受加密保护，请选择合适的位置。
      </p>

      <div class="switch-row">
        <div>
          <div class="label">保存位置</div>
          <div class="desc path mono" :title="download.dir || ''">
            {{ download.dir || '尚未选择，首次下载时会询问' }}
          </div>
        </div>
        <button class="ghost sm" type="button" @click="pickDownloadDir">选择目录</button>
      </div>
    </section>

    <section class="card">
      <h3>窗口</h3>
      <p class="hint">多窗口之间共享同一份设置与播放状态；托盘图标始终提供回到窗口的入口。</p>

      <div class="switch-row">
        <div>
          <div class="label">关闭时最小化到托盘</div>
          <div class="desc">关闭窗口后应用继续在托盘运行，进行中的下载不会中断</div>
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
      <h3>关于</h3>
      <div class="about">
        <div class="kv"><span>版本</span><span class="mono">{{ session.info?.version || '—' }}</span></div>
        <div class="kv"><span>平台</span><span class="mono">{{ session.info?.platform || '—' }}</span></div>
        <div class="kv">
          <span>数据目录</span>
          <span class="mono path" :title="session.info?.userData">{{ session.info?.userData || '—' }}</span>
        </div>
      </div>
      <div class="row" style="margin-top: 14px">
        <RouterLink to="/diagnostics"><button class="ghost" type="button">播放诊断</button></RouterLink>
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
} from '../state/embedded.js'
import LockDialog from '../components/LockDialog.vue'
import EmbeddedBackendDialog from '../components/EmbeddedBackendDialog.vue'
import EmbeddedAccountDialog from '../components/EmbeddedAccountDialog.vue'
import EmbeddedStorageDialog from '../components/EmbeddedStorageDialog.vue'

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
  toast(res?.ok ? '缓存已清除' : res?.error || '清除失败', res?.ok ? 'ok' : 'error')
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
const embeddedLine = computed(() => {
  const current = embedded.state
  if (!current) return '正在读取状态…'
  if (current.status === 'running') return `${current.backendLabel} 运行中 · ${current.origin}`
  if (current.status === 'downloading') {
    const progress = current.progress
    if (progress?.phase === 'download' && progress.total) {
      return `正在下载 ${current.backendLabel} ${Math.round((progress.received / progress.total) * 100)}%`
    }
    return '正在准备安装包…'
  }
  if (current.status === 'starting') return '正在启动…'
  if (current.status === 'error') return current.error || '启动失败'
  return current.installed ? `已下载 ${current.installed.tag}，尚未运行` : '未启用'
})

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
  attempt(() => patchSettings({ download: { dir: picked.path } }), { success: '下载目录已更新' })
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
  const ok = await attempt(() => unbindTotp(), { success: 'TOTP 已解绑' })
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
      toast(hadPassword ? '启动密码已更新' : '启动密码已启用', 'ok')
    } else if (dialog.mode === 'totp') {
      await confirmTotp(payload.code, payload.secret)
      toast('TOTP 已绑定', 'ok')
    } else {
      await disableLock({ password: payload.password, totp: payload.totp })
      toast('启动锁已关闭', 'ok')
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
