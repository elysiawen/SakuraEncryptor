/**
 * useFileResolver — Resolve encrypted file path to playable URL
 *
 * Shared logic used by PlayerView, ImageView, and MusicView.
 */
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getFileInfo } from './useAList.js'
import { getNameKeyFromSession, decryptName } from './useCrypto.js'

export function useFileResolver() {
  const route = useRoute()
  const router = useRouter()

  const decryptedName = ref('')
  const playUrl = ref('')
  const isEncrypted = ref(false)
  const isLocal = ref(false)
  const rawUrl = ref('')
  const rawSize = ref(0)
  const error = ref('')
  const loading = ref(true)

  const filePath = ref('')

  function parseRoute() {
    const p = route.params.path
    const fullPath = '/' + (Array.isArray(p) ? p.join('/') : (p || ''))
    filePath.value = fullPath
    const segments = fullPath.split('/').filter(Boolean)
    isLocal.value = segments[0] === 'local'
    return { fullPath, segments }
  }

  async function resolve() {
    loading.value = true
    error.value = ''

    try {
      const { fullPath, segments } = parseRoute()
      const encFileName = segments[segments.length - 1]

      // Decrypt filename
      const nameKey = await getNameKeyFromSession()
      if (nameKey) {
        let nameToDecrypt = encFileName
        if (nameToDecrypt.endsWith('.ske')) nameToDecrypt = nameToDecrypt.slice(0, -4)
        decryptedName.value = (await decryptName(nameToDecrypt, nameKey)) || encFileName
      } else {
        decryptedName.value = encFileName
      }

      // Get file URL
      if (isLocal.value) {
        const localPath = decodeURIComponent(fullPath.replace(/^\/?local\//, ''))
        rawUrl.value = `/ske-local/${localPath}`
        rawSize.value = 0
      } else {
        const info = await getFileInfo(fullPath)
        if (!info.url) throw new Error('无法获取文件链接')
        rawUrl.value = info.url
        rawSize.value = info.size || 0
      }

      // Construct decrypt proxy URL
      isEncrypted.value = encFileName.endsWith('.ske')
      if (isEncrypted.value) {
        // Proactively give SW the password
        const pwd = sessionStorage.getItem('ske_password')
        if (pwd && navigator.serviceWorker?.controller) {
          navigator.serviceWorker.controller.postMessage({ type: 'SET_PASSWORD', password: pwd })
        }

        if (isLocal.value) {
          playUrl.value = rawUrl.value
        } else {
          const sizeParam = rawSize.value ? `&size=${rawSize.value}` : ''
          playUrl.value = `/ske-decrypt/?url=${encodeURIComponent(rawUrl.value)}${sizeParam}&name=${encodeURIComponent(decryptedName.value)}`
        }
      } else {
        playUrl.value = rawUrl.value
      }

      loading.value = false
      return { playUrl: playUrl.value, decryptedName: decryptedName.value, isEncrypted: isEncrypted.value, isLocal: isLocal.value }
    } catch (err) {
      error.value = err.message || '文件加载失败'
      loading.value = false
      throw err
    }
  }

  function goBack() {
    const segments = filePath.value.split('/').filter(Boolean)
    if (segments[0] === 'local') {
      router.push('/local')
      return
    }
    segments.pop()
    router.push('/browse/' + segments.join('/'))
  }

  return {
    decryptedName,
    playUrl,
    isEncrypted,
    isLocal,
    rawUrl,
    rawSize,
    error,
    loading,
    filePath,
    resolve,
    goBack,
  }
}
