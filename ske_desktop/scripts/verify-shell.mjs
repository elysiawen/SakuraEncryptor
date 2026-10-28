/**
 * Smoke test for the app shell and the M1 platform layers.
 *
 * Runs with an isolated userData directory so it never touches a real install,
 * then drives the renderer through the same preload bridge the UI uses.
 *
 *   node node_modules/electron/cli.js scripts/verify-shell.mjs
 */
import { app, BrowserWindow } from 'electron'
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
import { createTray } from '../src/main/tray.js'
import { registerIpc } from '../src/main/ipc.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(here, '..')

// Must happen before ready: everything below reads app.getPath('userData').
const sandboxDir = path.join(os.tmpdir(), `ske-desktop-smoke-${process.pid}`)
app.setPath('userData', sandboxDir)

const checks = []
function assert(label, condition, detail = '') {
  checks.push({ label, ok: !!condition, detail })
}

setTimeout(() => {
  console.error('FAIL 超时退出')
  app.exit(1)
}, 45_000)

app.whenReady().then(async () => {
  const registry = new MediaRegistry()
  const storage = new Storage()
  const security = new Security()

  await storage.load()
  await security.load()

  const server = await startServer({ registry, rendererDir: path.join(rootDir, 'out/renderer') })

  let mainWindow = null
  const cloud = new CloudService()
  const localLibrary = new LocalLibrary()
  registerIpc({
    storage,
    security,
    registry,
    cloud,
    localLibrary,
    jobs: new JobQueue({}),
    downloads: new DownloadQueue({ registry, cloud, localLibrary }),
    serverInfo: () => ({ origin: server.origin, token: server.token }),
    mainWindow: () => mainWindow,
    openWindow: () => null,
  })

  mainWindow = new BrowserWindow({
    show: false,
    width: 1280,
    height: 820,
    webPreferences: {
      preload: path.join(rootDir, 'src/preload/index.cjs'),
      contextIsolation: true,
      sandbox: true,
    },
  })

  try {
    await mainWindow.loadURL(`${server.origin}/`)
    // Let Vue mount and the bootstrap() IPC round-trips settle.
    await new Promise((resolve) => setTimeout(resolve, 1200))

    const dom = await mainWindow.webContents.executeJavaScript(`({
      bridge: typeof window.ske === 'object' && typeof window.ske.vault?.list === 'function',
      sidebar: !!document.querySelector('.sidebar'),
      navItems: document.querySelectorAll('.nav-item').length,
      // The topbar is gone; the active nav item is the current section now.
      heading: (document.querySelector('.nav-item.active') || {}).textContent || '',
      bootError: !!document.querySelector('.boot-error'),
      downloadsBridge: typeof window.ske.downloads?.add === 'function',
      newWindowBridge: typeof window.ske.app?.newWindow === 'function',
      openFileBridge: typeof window.ske.app?.onOpenFile === 'function',
      stageBridge: typeof window.ske.player?.openWindow === 'function',
    })`)

    assert('preload 桥可用', dom.bridge)
    assert('侧边栏已渲染', dom.sidebar)
    assert('导航项数量为 5', dom.navItems === 5, `actual=${dom.navItems}`)
    assert('默认进入云端库', dom.heading.trim() === '云端库', `actual=${dom.heading.trim()}`)
    assert('没有启动错误页', !dom.bootError)
    assert('下载桥已挂载', dom.downloadsBridge)
    assert('多窗口桥已挂载', dom.newWindowBridge)
    assert('文件关联桥已挂载', dom.openFileBridge)
    assert('播放子窗口桥已挂载', dom.stageBridge)

    // --- player window ------------------------------------------------------
    // Video and images open here, so the shell must stay untouched behind it.
    const stageWin = new BrowserWindow({
      show: false,
      width: 900,
      height: 560,
      webPreferences: {
        preload: path.join(rootDir, 'src/preload/index.cjs'),
        contextIsolation: true,
        sandbox: true,
      },
    })

    try {
      await stageWin.loadURL(`${server.origin}/stage.html`)
      await new Promise((resolve) => setTimeout(resolve, 900))

      const stageDom = await stageWin.webContents.executeJavaScript(`({
        shell: !!document.querySelector('.stage-shell'),
        payloadBridge: typeof window.ske.player?.payload === 'function',
        windowBridge: typeof window.ske.window?.close === 'function',
        noSidebar: !document.querySelector('.sidebar'),
      })`)

      assert('播放子窗口可加载', stageDom.shell)
      assert('播放子窗口桥可用', stageDom.payloadBridge && stageDom.windowBridge)
      assert('播放子窗口不挂载主界面', stageDom.noSidebar)
    } finally {
      stageWin.destroy()
    }

    // --- tray ---------------------------------------------------------------
    let trayOk = false
    try {
      const tray = createTray({
        iconPath: path.join(rootDir, '..', 'icon.ico'),
        onShow: () => {},
        onNewWindow: () => {},
        onQuit: () => {},
      })
      trayOk = !tray.isDestroyed()
      tray.destroy()
    } catch {
      trayOk = false
    }
    assert('托盘图标可创建', trayOk)

    // --- profile round-trip through the real IPC handlers -------------------
    const saved = await mainWindow.webContents.executeJavaScript(`
      window.ske.vault.save({
        name: '冒烟测试',
        server: 'https://alist.example.com/',
        username: 'tester',
        password: 'alist-secret',
        skePassword: 'media-secret',
        basePath: '/encrypted',
      })
    `)
    assert('保存配置成功', saved.ok, saved.error || '')
    assert('服务器地址已规范化', saved.profile?.server === 'https://alist.example.com', saved.profile?.server)
    assert('配置不泄露密码', !JSON.stringify(saved).includes('alist-secret'))

    const relisted = await mainWindow.webContents.executeJavaScript('window.ske.vault.list()')
    assert('配置可列出', relisted.profiles?.length === 1, `count=${relisted.profiles?.length}`)
    assert('记录已存密码', relisted.profiles?.[0]?.hasSkePassword === true)

    // --- settings round-trip ------------------------------------------------
    const patched = await mainWindow.webContents.executeJavaScript(
      `window.ske.settings.patch({ cache: { blockEnabled: true, blockLimitMB: 2048 } })`,
    )
    assert('设置写入成功', patched.ok && patched.settings?.cache?.blockEnabled === true)
    assert('设置深合并保留其他字段', patched.settings?.cache?.thumbLimitMB === 128, patched.settings?.cache?.thumbLimitMB)

    // --- launch lock --------------------------------------------------------
    const enabled = await mainWindow.webContents.executeJavaScript(
      `window.ske.security.setPassword('lock-pass-123')`,
    )
    assert('启用启动锁', enabled.ok && enabled.state?.enabled === true, enabled.error || '')

    const wrong = await mainWindow.webContents.executeJavaScript(
      `window.ske.security.lock().then(() => window.ske.security.unlock('nope'))`,
    )
    assert('错误密码被拒绝', wrong.ok === false && /不正确/.test(wrong.error || ''), wrong.error || '')

    const right = await mainWindow.webContents.executeJavaScript(
      `window.ske.security.unlock('lock-pass-123')`,
    )
    assert('正确密码可解锁', right.ok && right.state?.unlocked === true, right.error || '')

    const changed = await mainWindow.webContents.executeJavaScript(
      `window.ske.security.setPassword('new-pass-456', 'wrong-current')`,
    )
    assert('修改密码需验证旧密码', changed.ok === false, changed.error || '')

    // --- persistence across a "restart" -------------------------------------
    const reloaded = new Storage()
    await reloaded.load()
    assert('配置已落盘', reloaded.listProfiles().profiles.length === 1)
    assert('密码以密封形式保存', !JSON.stringify(await fsp.readFile(reloaded.vaultPath, 'utf8')).includes('alist-secret'))

    const reloadedSecurity = new Security()
    await reloadedSecurity.load()
    assert('锁配置已落盘', reloadedSecurity.isEnabled())
    assert('落盘的是哈希而非明文', !JSON.stringify(await fsp.readFile(reloadedSecurity.filePath, 'utf8')).includes('lock-pass-123'))
  } catch (err) {
    assert('测试执行未抛异常', false, err.message)
  } finally {
    await registry.closeAll()
    await server.close()
    await fsp.rm(sandboxDir, { recursive: true, force: true }).catch(() => {})
  }

  const failed = checks.filter((c) => !c.ok)
  for (const check of checks) {
    console.log(`${check.ok ? 'PASS' : 'FAIL'}  ${check.label}${check.detail ? '  → ' + check.detail : ''}`)
  }
  console.log(`\n${checks.length - failed.length}/${checks.length} 项通过`)
  app.exit(failed.length ? 1 : 0)
})
