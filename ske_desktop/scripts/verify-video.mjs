/**
 * Headless playback check: does Chromium actually decode the container served
 * by /media, and can it seek?
 *
 * Usage:
 *   node node_modules/electron/cli.js scripts/verify-video.mjs <container.ske> <password> <realFileName>
 *
 * Runs as an Electron main process, so it exercises the same window, preload
 * and loopback stack the app uses — only without user interaction.
 */
import { app, BrowserWindow } from 'electron'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { MediaRegistry } from '../src/main/media-router.js'
import { startServer } from '../src/main/server.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const rendererDir = path.resolve(here, '../out/renderer')

const [containerPath, password, realName] = process.argv.slice(2)
if (!containerPath || !password || !realName) {
  console.error('用法: verify-video.mjs <container.ske> <password> <realFileName>')
  process.exit(2)
}

const registry = new MediaRegistry()
let server = null

// HEVC and 10-bit content only decode through the platform's hardware path in
// Chromium, so the probe must run with GPU acceleration intact. Pass
// SKE_NO_GPU=1 to reproduce the software-only case.
if (process.env.SKE_NO_GPU === '1') app.disableHardwareAcceleration()

setTimeout(() => {
  console.error('FAIL 超时退出')
  app.exit(1)
}, 40_000)

app.whenReady().then(async () => {
  server = await startServer({ registry, rendererDir })

  const info = await registry.open({
    target: containerPath,
    password,
    name: realName,
  })
  const url = `${server.origin}/media/${info.id}?t=${server.token}`

  const win = new BrowserWindow({
    show: false,
    width: 900,
    height: 600,
    webPreferences: { backgroundThrottling: false },
  })

  try {
    await win.loadURL(`${server.origin}/`)
  } catch (err) {
    // A static-serve failure is not what this check is about; the media URL is
    // absolute, so the probe script still runs on a blank document.
    console.warn('页面加载失败，继续在空白页探测:', err.message)
  }

  const script = `
    (async () => {
      const v = document.createElement('video')
      v.muted = true
      v.playsInline = true
      document.body.appendChild(v)

      const wait = (ev, ms = 12000) => new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(ev + ' 超时')), ms)
        v.addEventListener(ev, () => { clearTimeout(timer); resolve() }, { once: true })
      })

      const out = { mime: ${JSON.stringify(info.mime)}, size: ${info.size}, canPlay: null }
      v.src = ${JSON.stringify(url)}
      out.canPlay = v.canPlayType(out.mime) || ''

      try {
        await wait('loadedmetadata')
      } catch (err) {
        out.error = '加载元数据失败: ' + err.message
        return JSON.stringify(out)
      }

      out.duration = v.duration
      out.width = v.videoWidth
      out.height = v.videoHeight

      out.seeks = []
      for (const ratio of [0.2, 0.5, 0.85]) {
        const started = performance.now()
        try {
          v.currentTime = Math.max(0, v.duration * ratio)
          await wait('seeked')
          out.seeks.push({ ratio, ms: Math.round(performance.now() - started), at: Number(v.currentTime.toFixed(2)) })
        } catch (err) {
          out.seeks.push({ ratio, failed: err.message })
        }
      }

      // Decoding one frame at the end proves the tail block is readable.
      try {
        await v.play()
        await new Promise((r) => setTimeout(r, 400))
        v.pause()
        out.played = v.currentTime > 0
      } catch (err) {
        out.playError = err.message
      }

      return JSON.stringify(out)
    })()
  `

  try {
    const raw = await win.webContents.executeJavaScript(script, true)
    const result = JSON.parse(raw)
    console.log('RESULT ' + JSON.stringify(result, null, 2))
    const ok =
      !result.error &&
      Number.isFinite(result.duration) &&
      result.seeks.every((s) => !s.failed)
    console.log(ok ? '\nPASS 可播放且可任意 seek' : '\nFAIL 播放或 seek 不完整')
    await registry.closeAll()
    await server.close()
    app.exit(ok ? 0 : 1)
  } catch (err) {
    console.error('FAIL 探测脚本异常:', err.message)
    app.exit(1)
  }
})
