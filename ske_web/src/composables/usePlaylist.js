/**
 * usePlaylist — Playlist composable for media player views
 *
 * Lists sibling files in the same folder, filtered by media type.
 * Only works for AList (remote) files.
 */
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listDir } from './useAList.js'
import { getNameKeyFromSession, decryptName } from './useCrypto.js'
import { isAudio, isVideo, isImage } from './useFileDetection.js'

const ROUTE_PREFIX = { audio: '/listen/', video: '/play/', image: '/view/' }
const TYPE_CHECK = { audio: isAudio, video: isVideo, image: isImage }

export function usePlaylist(type) {
  const route = useRoute()
  const router = useRouter()

  const items = ref([])
  const loading = ref(false)
  const error = ref('')
  const currentIndex = ref(-1)
  const currentEncName = ref('')

  function parseRoute() {
    const p = route.params.path
    const fullPath = '/' + (Array.isArray(p) ? p.join('/') : (p || ''))
    const segments = fullPath.split('/').filter(Boolean)
    const isLocal = segments[0] === 'local'
    const encFileName = segments[segments.length - 1]
    const dirSegments = segments.slice(0, -1)
    const dirPath = '/' + dirSegments.join('/')
    return { fullPath, segments, isLocal, encFileName, dirPath, dirSegments }
  }

  async function load() {
    const { isLocal, encFileName, dirPath } = parseRoute()
    currentEncName.value = encFileName

    if (isLocal) {
      items.value = []
      return
    }

    loading.value = true
    error.value = ''

    try {
      const nameKey = await getNameKeyFromSession()
      const check = TYPE_CHECK[type]
      const data = await listDir(dirPath)

      // Get all non-directory entries
      const files = (data.content || []).filter(entry => !entry.is_dir)

      // Decrypt all names first, then filter by decrypted type
      const mapped = await Promise.all(
        files.map(async entry => {
          let decName = entry.name
          if (nameKey) {
            let nameToDecrypt = entry.name
            if (nameToDecrypt.endsWith('.ske')) nameToDecrypt = nameToDecrypt.slice(0, -4)
            const result = await decryptName(nameToDecrypt, nameKey)
            if (result) decName = result
          }
          return { encName: entry.name, decName, size: entry.size }
        })
      )

      const filtered = mapped.filter(item => check(item.decName))
      filtered.sort((a, b) => a.decName.localeCompare(b.decName, 'zh-CN'))
      items.value = filtered

      currentIndex.value = filtered.findIndex(item => item.encName === encFileName)
    } catch (err) {
      error.value = err.message || '加载播放列表失败'
    } finally {
      loading.value = false
    }
  }

  function navigateTo(item) {
    const { dirSegments } = parseRoute()
    const prefix = ROUTE_PREFIX[type]
    const parts = [...dirSegments, item.encName]
    router.push(prefix + parts.join('/'))
  }

  function goNext() {
    if (items.value.length === 0) return
    const next = (currentIndex.value + 1) % items.value.length
    navigateTo(items.value[next])
  }

  function goPrev() {
    if (items.value.length === 0) return
    const prev = (currentIndex.value - 1 + items.value.length) % items.value.length
    navigateTo(items.value[prev])
  }

  function goTo(item) {
    navigateTo(item)
  }

  return {
    items,
    loading,
    error,
    currentIndex,
    load,
    goNext,
    goPrev,
    goTo,
  }
}
