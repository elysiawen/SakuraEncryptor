/**
 * Player-window integration test.
 *
 * Opens the real stage.html window with a real decrypted image and checks three
 * things the unit tests cannot: the payload hand-off works, the window actually
 * renders media, and closing it releases the registry session (otherwise the
 * main process keeps decrypting for a window nobody can see).
 *
 *   node node_modules/electron/cli.js scripts/verify-stage.mjs
 */
import { BrowserWindow, app } from 'electron'
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { MediaRegistry } from '../src/main/media-router.js'
import { startServer } from '../src/main/server.js'
import { Storage } from '../src/main/storage.js'
import { Security } from '../src/main/security.js'
import { CloudService } from '../src/main/cloud.js'
import { LocalLibrary } from '../src/main/local-fs.js'
import { JobQueue } from '../src/main/jobs.js'
import { DownloadQueue } from '../src/main/downloads.js'
import { registerIpc } from '../src/main/ipc.js'
import { encryptFile } from '../src/main/ske-writer.js'
import { deriveNameKey, encryptName } from '../src/main/ske-names.js'

const PASSWORD = 'stage-pass'
const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const sandboxDir = path.join(os.tmpdir(), `ske-stage-${process.pid}`)

// 1×1 PNG: enough for <img> to reach the network layer, tiny enough to inline.
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

app.setPath('userData', sandboxDir)

// Closing the stage window must not take the test process down with it: without
// a listener Electron quits as soon as the last window closes.
app.on('window-all-closed', () => {})

const checks = []
function assert(label, condition, detail = '') {
  checks.push({ label, ok: !!condition, detail })
}

setTimeout(() => {
  console.error('FAIL 超时退出')
  app.exit(1)
}, 45_000)

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

app.whenReady().then(async () => {
  const registry = new MediaRegistry()
  const storage = new Storage()
  const security = new Security()
  const cloud = new CloudService()
  const localLibrary = new LocalLibrary()
  let server = null
  let stageWin = null
  let ipcApi = null

  try {
    await storage.load()
    await security.load()
    server = await startServer({ registry, rendererDir: path.join(rootDir, 'out/renderer') })

    const libraryDir = path.join(sandboxDir, 'library')
    await fsp.mkdir(libraryDir, { recursive: true })

    const pngPath = path.join(sandboxDir, 'pixel.png')
    await fsp.writeFile(pngPath, PNG)

    const nameKey = deriveNameKey(PASSWORD)
    await encryptFile({
      src: pngPath,
      dst: path.join(libraryDir, `${encryptName('pixel.png', nameKey)}.ske`),
      password: PASSWORD,
    })

    localLibrary.unlock(PASSWORD)
    localLibrary.setRoot(libraryDir)
    const listing = await localLibrary.list('/')
    const entry = listing.entries.find((item) => item.name === 'pixel.png')
    assert('图片容器已就绪', !!entry, JSON.stringify(listing.entries.map((e) => e.name)))

    const payload = {
      item: { mode: 'local', path: entry.path, name: entry.name, kind: 'image' },
      playlist: [],
      index: 0,
    }

    // The real main process consumes the payload once; mirror that here.
    let pendingPayload = payload

    ipcApi = registerIpc({
      storage,
      security,
      registry,
      cloud,
      localLibrary,
      jobs: new JobQueue({}),
      downloads: new DownloadQueue({ registry, cloud, localLibrary }),
      serverInfo: () => ({ origin: server.origin, token: server.token }),
      mainWindow: () => stageWin,
      openWindow: () => null,
      openStage: () => true,
      takeStagePayload: (id) => {
        if (!stageWin || id !== stageWin.webContents.id) return null
        const value = pendingPayload
        pendingPayload = null
        return value
      },
      onLock: () => {},
    })

    stageWin = new BrowserWindow({
      show: false,
      width: 900,
      height: 560,
      webPreferences: {
        preload: path.join(rootDir, 'src/preload/index.cjs'),
        contextIsolation: true,
        sandbox: true,
      },
    })

    const stageContentsId = stageWin.webContents.id
    stageWin.on('closed', () => ipcApi.releaseMediaFor(stageContentsId))

    await stageWin.loadURL(`${server.origin}/stage.html`)
    await wait(1200)

    const dom = await stageWin.webContents.executeJavaScript(`(() => {
      // The image stage lives inside the stage-shell wrapper (no bare .stage).
      const img = document.querySelector('.stage-shell img')
      return {
        shell: !!document.querySelector('.stage-shell'),
        noShellChrome: !document.querySelector('.sidebar') && !document.querySelector('.topbar'),
        imgSrc: img ? img.src : '',
        title: document.title,
        booting: !!document.querySelector('.boot'),
      }
    })()`)

    assert('播放窗口渲染出舞台', dom.shell)
    assert('播放窗口没有主界面外壳', dom.noShellChrome)
    assert('播放窗口显示图片元素', !!dom.imgSrc, dom.imgSrc)
    assert('图片走解密端点', dom.imgSrc.startsWith(`${server.origin}/media/`), dom.imgSrc)
    assert('窗口标题使用文件名', dom.title.includes('pixel.png'), dom.title)
    assert('加载态已结束', !dom.booting)
    assert('注册表里有 1 个会话', registry.sessions.size === 1, `actual=${registry.sessions.size}`)

    const second = await stageWin.webContents.executeJavaScript('window.ske.player.payload()')
    assert('初始载荷只消费一次', second.ok && second.payload === null, JSON.stringify(second.payload))

    stageWin.close()
    await wait(500)
    assert('关闭窗口释放解密会话', registry.sessions.size === 0, `actual=${registry.sessions.size}`)
  } catch (err) {
    assert('测试执行未抛异常', false, err.message)
  } finally {
    if (stageWin && !stageWin.isDestroyed()) stageWin.destroy()
    await registry.closeAll()
    await server?.close()
    await fsp.rm(sandboxDir, { recursive: true, force: true }).catch(() => {})
  }

  const failed = checks.filter((c) => !c.ok)
  // Synchronous writes: app.exit() would otherwise drop buffered pipe output.
  const lines = checks.map(
    (check) => `${check.ok ? 'PASS' : 'FAIL'}  ${check.label}${check.detail ? '  → ' + check.detail : ''}`,
  )
  lines.push(`\n${checks.length - failed.length}/${checks.length} 项通过\n`)
  fs.writeSync(1, lines.join('\n'))

  app.exit(failed.length ? 1 : 0)
})
