<template>
  <div class="music-view" :class="{ playing: isPlaying }">
    <div
      class="backdrop"
      :style="coverUrl ? { backgroundImage: `url(${coverUrl})` } : {}"
      aria-hidden="true"
    />
    <div class="scrim" aria-hidden="true" />

    <header class="titlebar">
      <div class="titlebar-left">
        <n-button quaternary size="small" @click="goBack">← {{ t('common.back') }}</n-button>
        <span class="badge">
          <span class="eq" :class="{ on: isPlaying }" aria-hidden="true"><i /><i /><i /></span>
          {{ t('music.nowPlaying') }}
        </span>
        <span class="title" :title="displayTitle">{{ displayTitle }}</span>
      </div>

      <div class="actions">
        <button class="icon" type="button" :disabled="!playUrl" :title="t('music.download')" @click="handleDownload">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 4v10" />
            <path d="M8 10.5 12 14.5l4-4" />
            <path d="M5 18h14" />
          </svg>
        </button>

        <button
          class="icon"
          :class="{ active: showPlaylist }"
          type="button"
          :title="t('player.playlist')"
          @click="showPlaylist = !showPlaylist"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round">
            <path d="M4 6h16M4 12h16M4 18h9" />
            <path d="M17 15.5 21 18l-4 2.5z" fill="currentColor" stroke="none" />
          </svg>
        </button>
      </div>
    </header>

    <main class="stage">
      <div v-if="error" class="empty">
        <p>{{ errorText }}</p>
        <n-button size="small" @click="init">{{ t('common.retry') }}</n-button>
      </div>

      <div v-else-if="!playUrl" class="empty">
        <span v-if="loading" class="spinner" />
        <p>{{ loading ? t('music.loading') : t('common.empty') }}</p>
      </div>

      <template v-else>
        <aside class="aside">
          <div class="art">
            <img v-if="coverUrl" :src="coverUrl" alt="" />
            <span v-else class="art-glyph" aria-hidden="true">♪</span>
          </div>

          <div class="titles">
            <h1 class="track" :title="displayTitle">{{ displayTitle }}</h1>
            <p v-if="byline" class="byline">{{ byline }}</p>
          </div>

          <p class="meta">
            <span class="chip">{{ mimeLabel }}</span>
            <span v-if="sizeLabel" class="chip">{{ sizeLabel }}</span>
          </p>
        </aside>

        <section class="lyric-area">
          <div
            ref="lyricBox"
            class="lyric-scroll"
            @wheel.passive="onLyricScroll"
            @touchmove.passive="onLyricScroll"
          >
            <p
              v-for="(line, index) in lyric.lines"
              :key="`${line.time}-${index}`"
              :ref="(element) => setLineRef(index, element)"
              class="lyric-line"
              :class="{ on: index === activeLine }"
              :style="{ opacity: lyricOpacity(index) }"
              @click="seekToLine(line.time)"
            >
              <span class="lyric-text">{{ line.text || '♪' }}</span>
            </p>
          </div>

          <!-- Outside the scroller: the scroll box reserves half a screen of
               padding for the scrolling lyrics, which would push the no-lyrics
               note below the middle of the stage. -->
          <div v-if="!lyric.lines.length" class="lyric-empty">
            <span class="art-glyph" aria-hidden="true">♪</span>
            <strong>{{ lyric.loading ? t('music.lyricsLoading') : t('music.noLyrics') }}</strong>
            <span>{{ t('music.lyricsHint') }}</span>
          </div>
        </section>
      </template>
    </main>

    <AudioPlayer
      v-if="playUrl && !error"
      ref="audioPlayerRef"
      :play-url="playUrl"
      :mode="playMode"
      :can-skip="playlist.items.value.length > 1"
      @error="(msg) => (error = msg)"
      @ended="onEnded"
      @prev="onPrev"
      @next="onNext"
      @cycle-mode="cycleMode"
    />

    <PlaylistPanel
      :items="playlist.items.value"
      :current-index="playlist.currentIndex.value"
      :visible="showPlaylist"
      :label="t('playlist.unitAudio')"
      @close="showPlaylist = false"
      @select="onPlaylistSelect"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import AudioPlayer from '../components/media-player/AudioPlayer.vue'
import PlaylistPanel from '../components/media-player/PlaylistPanel.vue'
import { useFileResolver } from '../composables/useFileResolver.js'
import { usePlaylist } from '../composables/usePlaylist.js'
import { activeLineIndex, useLyrics } from '../composables/useLyrics.js'
import { formatSize, getExt } from '../composables/useFileDetection.js'
import { readAudioTags } from '../composables/audioTags.js'
import { getServer } from '../composables/useRemoteStorage.js'
import { useDownloadManager } from '../composables/useDownloadManager.js'
import { toDownloadUrl } from '../composables/useFileDownload.js'
import { useUpstreamAlerts } from '../composables/useUpstreamAlerts.js'
import { useFailureDiagnosis } from '../composables/useDecryptDiagnosis.js'

const route = useRoute()
const { t } = useI18n()
const {
  decryptedName, playUrl, isEncrypted, isLocal, rawUrl, rawSize,
  error, loading, resolve, goBack,
} = useFileResolver()

const { dismissBanner } = useUpstreamAlerts()
const { detail, examine, reset: resetDiagnosis } = useFailureDiagnosis(() => playUrl.value)

// The audio element only knows "it did not load"; the worker can say why.
const errorText = computed(() => {
  if (!error.value) return ''
  if (detail.value) return detail.value
  return playUrl.value ? t('music.checking', { error: error.value }) : error.value
})

watch(error, (message) => {
  if (!message) {
    resetDiagnosis()
    return
  }
  dismissBanner()
  examine()
})

const playlist = usePlaylist('audio')
const { lyric, scan } = useLyrics()

const audioPlayerRef = ref(null)
const showPlaylist = ref(false)
const playMode = ref('list')
const { addTask } = useDownloadManager()

const coverUrl = ref('')
const meta = reactive({ title: '', artist: '', album: '' })
const lyricBox = ref(null)
/** Elements of the rendered lines, indexed the same way as `lyric.lines`. */
const lineEls = []
/** Pause auto-follow for a moment after the user scrolls by hand. */
let manualScrollUntil = 0
/** Guards against a slow tag/lyric read landing after the track changed. */
let loadToken = 0

const isPlaying = computed(() => audioPlayerRef.value?.isPlaying || false)
const displayTitle = computed(() => meta.title || baseName(decryptedName.value) || t('music.noTitle'))
const byline = computed(() => [meta.artist, meta.album].filter(Boolean).join(' · '))
const mimeLabel = computed(() => getExt(decryptedName.value).toUpperCase() || 'AUDIO')
const sizeLabel = computed(() => formatSize(rawSize.value))

const activeLine = computed(() => activeLineIndex(lyric.lines, audioPlayerRef.value?.currentTime || 0))

/** Strip the last extension: "a.b.mp3" -> "a.b". */
function baseName(name) {
  const i = name.lastIndexOf('.')
  return i > 0 ? name.slice(0, i) : name
}

/** Distance from the active line fades the rest, as the desktop player does. */
function lyricOpacity(index) {
  const active = activeLine.value
  if (active < 0) return 0.62
  const distance = Math.abs(index - active)
  if (distance === 0) return 1
  if (distance === 1) return 0.46
  if (distance === 2) return 0.3
  if (distance === 3) return 0.2
  return 0.12
}

function setLineRef(index, element) {
  if (element instanceof HTMLElement) lineEls[index] = element
}

/** Put the active line at ~36% height instead of dead centre. */
function scrollToActive(index) {
  const box = lyricBox.value
  const element = lineEls[index]
  if (!box || !element) return
  const top = element.offsetTop - box.clientHeight * 0.36 + element.clientHeight / 2
  box.scrollTo({ top: Math.max(0, top), behavior: 'smooth' })
}

function onLyricScroll() {
  manualScrollUntil = Date.now() + 4000
}

function seekToLine(time) {
  audioPlayerRef.value?.seekTo(time)
}

watch(activeLine, (index) => {
  if (index < 0 || Date.now() < manualScrollUntil) return
  void nextTick(() => scrollToActive(index))
})

function releaseCover() {
  if (coverUrl.value.startsWith('blob:')) URL.revokeObjectURL(coverUrl.value)
  coverUrl.value = ''
}

/** Candidate tag sources, most direct first. */
function tagUrls() {
  if (isLocal.value || isEncrypted.value) return [playUrl.value]

  const urls = []
  if (rawUrl.value) urls.push(rawUrl.value)

  // A plaintext direct link can sit on a CDN that rejects cross-origin ranged
  // reads; the provider's own /d/ route is the one the lyric fetch already uses.
  const server = getServer()
  const p = route.params.path
  const segments = (Array.isArray(p) ? p.join('/') : (p || '')).split('/').filter(Boolean)
  if (server && segments.length) {
    urls.push(`${server.replace(/\/+$/, '')}/d/${segments.map(encodeURIComponent).join('/')}`)
  }
  return urls
}

async function readTags(token) {
  if (!playUrl.value) return

  for (const url of tagUrls()) {
    const tags = await readAudioTags(url)
    if (token !== loadToken) {
      // The track changed while the walk was in flight.
      if (tags.cover) URL.revokeObjectURL(tags.cover)
      return
    }

    if (!tags.cover && !tags.title && !tags.artist && !tags.album) continue
    meta.title = tags.title
    meta.artist = tags.artist
    meta.album = tags.album
    coverUrl.value = tags.cover
    return
  }
}

function scanLyrics() {
  if (!playUrl.value || !decryptedName.value) return
  lineEls.length = 0
  manualScrollUntil = 0
  if (lyricBox.value) lyricBox.value.scrollTop = 0
  scan(playlist.dirPath.value, decryptedName.value, isLocal.value ? 'local' : 'alist')
}

function handleDownload() {
  // Encrypted files must be downloaded through the Service Worker, otherwise
  // the user only gets the .ske container instead of the plaintext audio.
  if (isEncrypted.value && !isLocal.value && playUrl.value) {
    addTask({
      url: toDownloadUrl(playUrl.value),
      name: decryptedName.value || 'download',
      total: 0, // plaintext size comes from the response Content-Length
    })
    return
  }

  if (!rawUrl.value) return
  addTask({
    url: rawUrl.value,
    name: decryptedName.value || 'download',
    total: rawSize.value || 0,
  })
}

async function init() {
  const token = (loadToken += 1)
  releaseCover()
  meta.title = meta.artist = meta.album = ''
  lineEls.length = 0

  try {
    await resolve()
  } catch {
    return
  }
  if (token !== loadToken) return

  playlist.load()
  readTags(token)
  scanLyrics()
}

function onPlaylistSelect(item) {
  showPlaylist.value = false
  playlist.goTo(item)
}

function onPrev() {
  playlist.goPrev()
}

function onNext() {
  playlist.goNext()
}

function cycleMode() {
  const modes = ['list', 'one', 'shuffle']
  playMode.value = modes[(modes.indexOf(playMode.value) + 1) % modes.length]
}

function onEnded() {
  if (playMode.value === 'one') {
    audioPlayerRef.value?.replay()
    return
  }

  const items = playlist.items.value
  if (!items.length) return

  if (playMode.value === 'shuffle') {
    const current = playlist.currentIndex.value
    let next = Math.floor(Math.random() * items.length)
    if (items.length > 1 && next === current) next = (next + 1) % items.length
    playlist.goTo(items[next])
    return
  }

  playlist.goNext()
}

const SEEK_STEP = 5

function onKeydown(event) {
  const target = event.target
  if (
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    (target instanceof HTMLElement && target.closest('button'))
  ) {
    return
  }

  if (event.key === 'Escape') {
    goBack()
    return
  }

  if (event.code === 'Space') {
    event.preventDefault()
    audioPlayerRef.value?.togglePlay()
  } else if (event.key === 'ArrowLeft') {
    event.preventDefault()
    audioPlayerRef.value?.seekBy(-SEEK_STEP)
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    audioPlayerRef.value?.seekBy(SEEK_STEP)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    audioPlayerRef.value?.adjustVolume(0.05)
  } else if (event.key === 'ArrowDown') {
    event.preventDefault()
    audioPlayerRef.value?.adjustVolume(-0.05)
  } else if (event.key === 'm' || event.key === 'M') {
    audioPlayerRef.value?.toggleMute()
  }
}

watch(() => route.params.path, () => init())

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  init()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  // The cover is a blob URL owned by this page; leaving it registered keeps
  // the decoded artwork alive for the rest of the session.
  releaseCover()
})
</script>

<style scoped>
.music-view {
  position: relative;
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-primary);
  color: var(--text-primary);
}

/* ── Ambience: the cover, blown up and blurred, under a theme-tinted scrim ── */
.backdrop {
  position: absolute;
  inset: -14%;
  background-size: cover;
  background-position: center;
  filter: blur(64px) saturate(1.5);
  transform: scale(1.18);
  opacity: 0.5;
  pointer-events: none;
}

.scrim {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(75% 60% at 16% 0%, rgba(139, 92, 246, 0.26), transparent 64%),
    radial-gradient(70% 60% at 92% 100%, rgba(6, 182, 212, 0.14), transparent 62%),
    linear-gradient(
      180deg,
      rgba(10, 10, 20, 0.76) 0%,
      rgba(10, 10, 20, 0.92) 55%,
      rgba(10, 10, 20, 0.97) 100%
    );
  backdrop-filter: blur(3px);
  -webkit-backdrop-filter: blur(3px);
}

/* ── Title bar ── */
.titlebar {
  position: relative;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 56px;
  padding: 0 16px;
}

.titlebar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: none;
  padding: 5px 12px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 650;
  letter-spacing: 0.02em;
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid var(--border-glass);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}

.eq {
  display: inline-flex;
  align-items: flex-end;
  gap: 2.5px;
  height: 11px;
}

.eq i {
  width: 2.5px;
  border-radius: 2px;
  background: var(--accent-start);
  height: 4px;
}

.eq.on i {
  animation: eq 0.9s ease-in-out infinite;
}

.eq.on i:nth-child(2) {
  animation-delay: 0.18s;
}

.eq.on i:nth-child(3) {
  animation-delay: 0.36s;
}

@keyframes eq {
  0%, 100% { height: 3px; }
  50% { height: 11px; }
}

.title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--text-secondary);
}

.actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: none;
}

.icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.icon svg {
  width: 16px;
  height: 16px;
}

.icon:hover:not(:disabled),
.icon.active {
  background: rgba(255, 255, 255, 0.1);
  color: var(--text-primary);
}

.icon:disabled {
  opacity: 0.35;
  cursor: default;
}

/* ── Stage: cover left, lyrics right ── */
.stage {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
  display: grid;
  /*
   * Two capped columns, centred as a block: the lyric column gives way to a
   * long line but never stretches across the whole window, and short lyrics sit
   * near the middle instead of hugging the cover.
   */
  grid-template-columns:
    clamp(200px, 26vw, 310px)
    minmax(280px, clamp(360px, 44vw, 660px));
  justify-content: center;
  column-gap: clamp(24px, 4.5vw, 84px);
  padding: 8px 40px 12px;
}

.aside {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 14px;
  min-width: 0;
}

.art {
  position: relative;
  width: 100%;
  aspect-ratio: 1;
  border-radius: 18px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--border-glass);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
}

.art img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.art-glyph {
  font-size: 44px;
  color: var(--text-muted);
  opacity: 0.7;
}

.titles {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.track {
  margin: 0;
  font-size: clamp(19px, 2vw, 26px);
  font-weight: 700;
  line-height: 1.25;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.byline {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-secondary);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.meta {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  padding: 3px 8px;
  border-radius: 999px;
  border: 1px solid var(--border-glass);
  background: rgba(255, 255, 255, 0.04);
  font-size: 11px;
  color: var(--text-secondary);
}

/* ── Lyrics ── */
.lyric-area {
  flex: 1;
  min-width: 0;
  position: relative;
}

.lyric-scroll {
  /* Absolute so `offsetTop` of a line is measured inside this box, which is
     what the scroll-to-active maths assumes. */
  position: absolute;
  inset: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  /* Half a screen of air above and below: the first and last lines can reach
     the middle of the stage instead of stopping at its edge. */
  padding: 32vh 4px 32vh 2px;
  scrollbar-width: none;
  /* Multi-stop so the fade has no visible edge, and it works on any backdrop
     because it only fades the lyrics themselves. */
  mask-image: linear-gradient(
    180deg,
    transparent 0%,
    rgba(0, 0, 0, 0.2) 7%,
    rgba(0, 0, 0, 0.62) 17%,
    #000 30%,
    #000 70%,
    rgba(0, 0, 0, 0.62) 83%,
    rgba(0, 0, 0, 0.2) 93%,
    transparent 100%
  );
  -webkit-mask-image: linear-gradient(
    180deg,
    transparent 0%,
    rgba(0, 0, 0, 0.2) 7%,
    rgba(0, 0, 0, 0.62) 17%,
    #000 30%,
    #000 70%,
    rgba(0, 0, 0, 0.62) 83%,
    rgba(0, 0, 0, 0.2) 93%,
    transparent 100%
  );
}

.lyric-scroll::-webkit-scrollbar {
  display: none;
}

.lyric-line {
  margin: 0;
  padding: 9px 0;
  font-size: clamp(19px, 1.9vw, 27px);
  font-weight: 700;
  line-height: 1.4;
  letter-spacing: -0.01em;
  color: var(--text-primary);
  cursor: pointer;
  /* Scaling happens from the left edge, so the text grows into the empty
     column on the right instead of shifting the whole block. */
  transform-origin: left center;
  transition: opacity 0.45s ease, color 0.3s ease, transform 0.3s ease;
  text-align: left;
  word-break: break-word;
}

.lyric-line:hover .lyric-text {
  color: #a78bfa;
}

.lyric-line.on {
  color: #a78bfa;
  transform: scale(1.045);
}

.lyric-line.on .lyric-text {
  text-shadow: 0 0 26px rgba(167, 139, 250, 0.4);
}

.lyric-text {
  display: block;
  transition: color 0.25s ease;
}

/* Centred in the lyric column itself, not in the scroller: the scroller's
   half-screen paddings are for scrolling lyrics, not for this note. */
.lyric-empty {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-muted);
  text-align: center;
}

.lyric-empty .art-glyph {
  font-size: 32px;
}

.lyric-empty strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}

.lyric-empty span:last-child {
  font-size: 12px;
  color: var(--text-muted);
}

/* ── Empty / error states ── */
.empty {
  grid-column: 1 / -1;
  align-self: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
}

.spinner {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.16);
  border-top-color: var(--accent-start);
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Short viewports: the cover gives way before the lyrics do. */
@media (max-height: 560px) {
  .stage {
    grid-template-columns:
      clamp(150px, 22vw, 210px)
      minmax(260px, clamp(320px, 46vw, 620px));
    column-gap: clamp(18px, 3vw, 48px);
    padding: 4px 24px 8px;
  }

  .lyric-scroll {
    padding: 18vh 4px 18vh 2px;
  }

  .lyric-line {
    font-size: 17px;
  }
}

/* Narrow screens: the cover moves above the lyrics instead of squeezing it. */
@media (max-width: 860px) {
  .stage {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
    row-gap: 12px;
    padding: 8px 20px 12px;
  }

  .aside {
    flex-direction: row;
    align-items: center;
    justify-content: flex-start;
    gap: 14px;
  }

  .art {
    width: 88px;
    flex: 0 0 88px;
    border-radius: 14px;
  }

  .titles {
    flex: 1;
  }

  .track {
    font-size: 18px;
    -webkit-line-clamp: 1;
  }

  .lyric-scroll {
    padding: 14vh 4px 14vh 2px;
  }

  .lyric-line {
    font-size: 18px;
  }
}

@media (max-width: 600px) {
  .titlebar {
    padding: 0 10px;
    gap: 8px;
  }

  .title {
    display: none;
  }

  .badge {
    padding: 4px 10px;
    font-size: 11px;
  }

  .stage {
    padding: 6px 12px 10px;
  }
}
</style>
