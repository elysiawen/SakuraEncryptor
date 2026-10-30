/**
 * Music-window integration test.
 *
 * The music window owns no stream: it mirrors the shell's transport over IPC.
 * That link is easy to break silently — the window then just sits on "正在解密"
 * until its fallback message appears — so this test drives the real channels:
 *
 *   shell (fake) --publish--> main --> stage window
 *   stage window --command--> main --> shell (fake)
 *
 * Run with: npm run verify:music
 */
import { BrowserWindow, app, webContents } from 'electron'
import fs from 'node:fs'
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

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const sandboxDir = path.join(os.tmpdir(), `ske-music-${process.pid}`)

app.setPath('userData', sandboxDir)
app.on('window-all-closed', () => {})

const checks = []
function assert(label, condition, detail = '') {
  checks.push({ label, ok: !!condition, detail })
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

setTimeout(() => {
  console.error('FAIL 超时退出')
  app.exit(1)
}, 45_000)

app.whenReady().then(async () => {
  const registry = new MediaRegistry()
  const storage = new Storage()
  const security = new Security()
  const cloud = new CloudService()
  const localLibrary = new LocalLibrary()

  let server = null
  let shellWin = null
  let stageWin = null
  let stageOwnerId = 0
  let pendingPayload = null
  let ipcApi = null

  function openStage(payload, ownerId = 0) {
    if (ownerId) stageOwnerId = ownerId

    if (stageWin && !stageWin.isDestroyed()) {
      stageWin.webContents.send('stage:load', payload)
      return true
    }

    const win = new BrowserWindow({
      show: false,
      frame: false,
      width: 1024,
      height: 640,
      webPreferences: {
        preload: path.join(rootDir, 'src/preload/index.cjs'),
        contextIsolation: true,
        sandbox: true,
      },
    })

    stageWin = win
    pendingPayload = payload
    win.on('closed', () => {
      if (stageWin === win) stageWin = null
    })
    win.loadURL(`${server.origin}/stage.html`)
    return true
  }

  try {
    await storage.load()
    await security.load()
    server = await startServer({
      registry,
      rendererDir: path.join(rootDir, 'out/renderer'),
    })

    ipcApi = registerIpc({
      storage,
      security,
      registry,
      cloud,
      localLibrary,
      jobs: new JobQueue({}),
      downloads: new DownloadQueue({ registry, cloud, localLibrary }),
      serverInfo: () => ({ origin: server.origin, token: server.token }),
      mainWindow: () => shellWin,
      openWindow: () => null,
      openStage,
      takeStagePayload: () => {
        const value = pendingPayload
        pendingPayload = null
        return value
      },
      stageWindows: () => (stageWin ? [stageWin] : []),
      stageOwnerOf: (id) =>
        stageWin && stageWin.webContents.id === id && stageOwnerId ? webContents.fromId(stageOwnerId) : null,
      closeMusicStage: () => {},
      onLock: () => {},
    })

    // Stand-in for the shell: it answers `sync` with a canned transport, which
    // is exactly what App.vue publishes.
    shellWin = new BrowserWindow({
      show: false,
      webPreferences: {
        preload: path.join(rootDir, 'src/preload/index.cjs'),
        contextIsolation: true,
        sandbox: true,
      },
    })
    await shellWin.loadURL('data:text/html,<title>shell</title><body>shell</body>')

    await shellWin.webContents.executeJavaScript(`
      window.__commands = [];
      window.ske.player.onCommand((command) => {
        window.__commands.push(command);
        window.ske.player.publish({
          url: 'http://127.0.0.1:1/fake-stream',
          name: '合成音乐.flac',
          mime: 'audio/flac',
          size: 18 * 1024 * 1024,
          playing: true,
          currentTime: 42,
          duration: 200,
          volume: 0.8,
          muted: false,
          playlist: [
            { name: '合成音乐.flac', path: '/a' },
            { name: '第二首.flac', path: '/b' },
          ],
          index: 0,
          loading: false,
          error: '',
        });
      });
      true;
    `)

    const opened = await shellWin.webContents.executeJavaScript(`
      window.ske.player.openWindow({
        item: { mode: 'local', path: '/a', name: '合成音乐.flac', kind: 'audio' },
        playlist: [
          { name: '合成音乐.flac', path: '/a' },
          { name: '第二首.flac', path: '/b' },
        ],
        index: 0,
      })
    `)
    assert('音乐窗口已打开', opened?.ok && opened.opened, JSON.stringify(opened))

    await wait(1200)
    assert('主进程记住了打开者', stageOwnerId === shellWin.webContents.id, `owner=${stageOwnerId}`)

    // The window itself asks for the current transport on mount.
    const commands = await shellWin.webContents.executeJavaScript('window.__commands')
    assert(
      '窗口打开后请求了状态',
      commands.some((command) => command?.type === 'sync'),
      JSON.stringify(commands),
    )

    const dom = await stageWin.webContents.executeJavaScript(`(() => {
      const seek = document.querySelector('.seek');
      return {
        titlebar: !!document.querySelector('.titlebar'),
        dragging: !!document.querySelector('.titlebar'),
        track: document.querySelector('.track')?.textContent?.trim() || '',
        elapsed: document.querySelector('.seek-row .time')?.textContent?.trim() || '',
        seekMax: seek ? Number(seek.max) : 0,
        empty: !!document.querySelector('.empty'),
        queueOpen: !!document.querySelector('.queue'),
      };
    })()`)

    assert('音乐窗口有自己的标题栏', dom.titlebar)
    assert('状态已同步到窗口', !dom.empty, JSON.stringify(dom))
    // The stream cannot be read here, so the title falls back to the file name
    // without its extension.
    assert('曲名回退到文件名', dom.track === '合成音乐', dom.track)
    assert('进度来自主窗口状态', dom.elapsed === '0:42', dom.elapsed)
    assert('时长来自主窗口状态', dom.seekMax === 200, `max=${dom.seekMax}`)

    // Clicking play must reach the shell, not a local audio element.
    await stageWin.webContents.executeJavaScript(
      "document.querySelector('.round.main').click(); true",
    )
    await wait(300)
    const afterClick = await shellWin.webContents.executeJavaScript('window.__commands')
    assert(
      '播放按钮的指令到达主窗口',
      afterClick.some((command) => command?.type === 'toggle'),
      JSON.stringify(afterClick),
    )

    // Closing the window must not touch the shell: it is the one playing.
    stageWin.close()
    await wait(400)
    assert('关闭音乐窗口后主窗口仍在', !shellWin.isDestroyed())

    const stillAlive = await shellWin.webContents.executeJavaScript('1 + 1')
    assert('主窗口仍可执行脚本', stillAlive === 2)
    assert('注册表没有泄漏会话', registry.sessions.size === 0, `actual=${registry.sessions.size}`)
  } catch (err) {
    assert('测试执行未抛异常', false, err.stack || err.message)
  } finally {
    if (stageWin && !stageWin.isDestroyed()) stageWin.destroy()
    if (shellWin && !shellWin.isDestroyed()) shellWin.destroy()
    await registry.closeAll()
    await server?.close()
    await fs.promises.rm(sandboxDir, { recursive: true, force: true }).catch(() => {})
  }

  const failed = checks.filter((check) => !check.ok)
  const lines = checks.map(
    (check) => `${check.ok ? 'PASS' : 'FAIL'}  ${check.label}${check.detail ? '  → ' + check.detail : ''}`,
  )
  lines.push(`\n${checks.length - failed.length}/${checks.length} 项通过\n`)
  fs.writeSync(1, lines.join('\n'))

  app.exit(failed.length ? 1 : 0)
})
