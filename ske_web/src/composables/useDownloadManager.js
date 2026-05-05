/**
 * useDownloadManager — Global download task manager
 *
 * Uses fetch streaming + Range requests for true pause/resume support.
 */
import { reactive, computed } from 'vue'

const tasks = reactive([])
let nextId = 1
const SPEED_INTERVAL_MS = 500

function addTask({ url, name, total = 0 }) {
  const id = nextId++
  const knownTotal = Number(total) > 0 ? Number(total) : 0
  const task = reactive({
    id,
    url,
    name: name || 'download',
    status: 'downloading', // downloading | paused | completed | failed | cancelled
    progress: 0,
    loaded: 0,
    total: knownTotal,
    knownTotal,
    controller: null,
    error: '',
    speed: 0,
    canResume: true,
    chunks: [],
    _lastLoaded: 0,
    _lastTime: 0,
    _speedTimer: null,
  })
  tasks.push(task)
  startDownload(task, { resumeFromPartial: false })
  return task
}

function resetTaskData(task) {
  task.progress = 0
  task.loaded = 0
  task.total = task.knownTotal || 0
  task.chunks = []
}

function stopSpeedTimer(task) {
  if (task._speedTimer) {
    clearInterval(task._speedTimer)
    task._speedTimer = null
  }
  task.speed = 0
}

function startSpeedTimer(task) {
  stopSpeedTimer(task)
  task._lastLoaded = task.loaded
  task._lastTime = Date.now()
  task._speedTimer = setInterval(() => {
    const now = Date.now()
    const elapsed = (now - task._lastTime) / 1000
    if (elapsed > 0 && task.status === 'downloading') {
      task.speed = (task.loaded - task._lastLoaded) / elapsed
      task._lastLoaded = task.loaded
      task._lastTime = now
    }
  }, SPEED_INTERVAL_MS)
}

function updateProgress(task) {
  if (task.total > 0) {
    task.progress = Math.min(99, Math.round((task.loaded / task.total) * 100))
  } else {
    task.progress = 0
  }
}

function parseTotalFromContentRange(contentRange) {
  if (!contentRange) return 0
  const match = contentRange.match(/bytes\s+\d+-\d+\/(\d+|\*)/i)
  if (!match || match[1] === '*') return 0
  const total = Number(match[1])
  return Number.isFinite(total) ? total : 0
}

function triggerBrowserDownload(task, blob) {
  const blobUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = blobUrl
  a.download = task.name
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(blobUrl)
}

async function startDownload(task, { resumeFromPartial }) {
  const shouldResume = resumeFromPartial && task.loaded > 0
  const startOffset = shouldResume ? task.loaded : 0

  if (!shouldResume) {
    resetTaskData(task)
  }

  task.status = 'downloading'
  task.error = ''
  startSpeedTimer(task)

  const controller = new AbortController()
  task.controller = controller

  try {
    const headers = {}
    if (startOffset > 0) {
      headers.Range = `bytes=${startOffset}-`
    }

    const res = await fetch(task.url, {
      method: 'GET',
      headers,
      signal: controller.signal,
    })

    if (startOffset > 0 && res.status === 200) {
      // Server ignored the Range header. Restart from scratch to avoid a corrupt file.
      resetTaskData(task)
    }

    if (!(res.status >= 200 && res.status < 300)) {
      throw new Error(`HTTP ${res.status}`)
    }

    const acceptRanges = (res.headers.get('accept-ranges') || '').toLowerCase()
    task.canResume = res.status === 206 || acceptRanges === 'bytes'

    const totalFromRange = parseTotalFromContentRange(res.headers.get('content-range'))
    const contentLength = Number(res.headers.get('content-length')) || 0
    if (totalFromRange > 0) {
      task.total = totalFromRange
    } else if (task.knownTotal > 0) {
      task.total = task.knownTotal
    } else if (res.status === 206 && contentLength > 0) {
      task.total = task.loaded + contentLength
    } else if (contentLength > 0 && task.loaded === 0) {
      task.total = contentLength
    }
    updateProgress(task)

    if (!res.body) {
      const blob = await res.blob()
      task.chunks.push(blob)
      task.loaded = startOffset + blob.size
      task.total = task.total || task.loaded
    } else {
      const reader = res.body.getReader()
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        if (value?.length) {
          task.chunks.push(value)
          task.loaded += value.length
          updateProgress(task)
        }
      }
    }

    stopSpeedTimer(task)
    const blob = new Blob(task.chunks)
    triggerBrowserDownload(task, blob)
    task.status = 'completed'
    task.total = task.total || blob.size || task.loaded
    task.loaded = task.total
    task.progress = 100
    task.controller = null
  } catch (err) {
    stopSpeedTimer(task)
    task.controller = null

    if (err?.name === 'AbortError') {
      // status is already set by pause/cancel, keep buffered chunks for resume when paused
      return
    }

    task.status = 'failed'
    task.error = err?.message || '网络错误'
  }
}

function cancelTask(id) {
  const task = tasks.find(t => t.id === id)
  if (task && (task.status === 'downloading' || task.status === 'paused')) {
    task.status = 'cancelled'
    stopSpeedTimer(task)
    if (task.controller) {
      task.controller.abort()
      task.controller = null
    }
    task.chunks = []
  }
}

function pauseTask(id) {
  const task = tasks.find(t => t.id === id)
  if (task && task.status === 'downloading') {
    task.status = 'paused'
    stopSpeedTimer(task)
    if (task.controller) {
      task.controller.abort()
      task.controller = null
    }
  }
}

function resumeTask(id) {
  const task = tasks.find(t => t.id === id)
  if (task && task.status === 'paused') {
    // Always try a ranged resume first. Some file servers support Range but do
    // not expose Accept-Ranges to CORS, so pre-judging support causes false negatives.
    startDownload(task, { resumeFromPartial: true })
  }
}

function retryTask(id) {
  const task = tasks.find(t => t.id === id)
  if (task && (task.status === 'failed' || task.status === 'cancelled')) {
    resetTaskData(task)
    startDownload(task, { resumeFromPartial: false })
  }
}

function removeTask(id) {
  const idx = tasks.findIndex(t => t.id === id)
  if (idx !== -1) {
    const task = tasks[idx]
    if (task.controller) {
      task.status = 'cancelled'
      task.controller.abort()
    }
    stopSpeedTimer(task)
    tasks.splice(idx, 1)
  }
}

function clearCompleted() {
  for (let i = tasks.length - 1; i >= 0; i--) {
    if (tasks[i].status === 'completed' || tasks[i].status === 'cancelled') {
      stopSpeedTimer(tasks[i])
      tasks.splice(i, 1)
    }
  }
}

const activeCount = computed(() => tasks.filter(t => t.status === 'downloading').length)
const hasTasks = computed(() => tasks.length > 0)

export function useDownloadManager() {
  return {
    tasks,
    activeCount,
    hasTasks,
    addTask,
    cancelTask,
    pauseTask,
    resumeTask,
    retryTask,
    removeTask,
    clearCompleted,
  }
}
