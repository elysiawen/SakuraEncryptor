/**
 * Dev launcher: Vite first, then Electron once the page is actually reachable.
 *
 * Keeping this in plain Node avoids a `concurrently`-style dependency and makes
 * the startup order explicit — Electron loads http://localhost:5173, and a
 * premature launch would only show a blank window.
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const root = path.resolve(import.meta.dirname, '..')
const port = 5173
const devUrl = `http://localhost:${port}`

const children = []

function run(command, args, options = {}) {
  const child = spawn(command, args, { cwd: root, stdio: 'inherit', ...options })
  children.push(child)
  return child
}

function shutdown(code = 0) {
  for (const child of children) {
    if (!child.killed) child.kill()
  }
  process.exit(code)
}

async function waitForServer(url, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(1500) })
      if (res.ok) return true
    } catch {
      /* not up yet */
    }
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
  return false
}

process.on('SIGINT', () => shutdown(0))
process.on('SIGTERM', () => shutdown(0))

const vite = run(process.execPath, ['node_modules/vite/bin/vite.js', '--port', String(port)])
vite.on('exit', (code) => shutdown(code ?? 0))

if (!(await waitForServer(devUrl))) {
  console.error('[dev] Vite 未能在 60s 内启动')
  shutdown(1)
}

let electron = null
let restarting = false
let restartTimer = null

function startElectron() {
  electron = run(process.execPath, ['node_modules/electron/cli.js', '.'], {
    env: { ...process.env, SKE_DEV: '1', SKE_DEV_URL: devUrl },
  })
  electron.on('exit', (code) => {
    // A restart kills the child on purpose; anything else ends the dev session.
    if (restarting) return
    shutdown(code ?? 0)
  })
}

/**
 * Vite reloads the renderer; nothing reloads the main process.
 *
 * Without this, an edit under `src/main` looks applied — the window even
 * reloads — while the old code keeps running, which is exactly how a fixed bug
 * can still appear broken. Restart Electron whenever main or preload changes.
 *
 * Note this does not cover a production build: there the app must be quit
 * completely, and closing the window only hides it to the tray when that
 * setting is on.
 */
function watchMainProcess() {
  const watcher = fs.watch(path.join(root, 'src'), { recursive: true }, (_event, file) => {
    if (!file) return
    const changed = String(file).replace(/\\/g, '/')
    if (!changed.startsWith('main/') && !changed.startsWith('preload/')) return

    // Editors write in bursts (temp file + rename); restart once per burst.
    clearTimeout(restartTimer)
    restartTimer = setTimeout(() => {
      console.log('[dev] 主进程已变更，重启 Electron…')
      restarting = true
      electron?.kill()
      setTimeout(() => {
        restarting = false
        startElectron()
      }, 200)
    }, 150)
  })
  watcher.on('error', () => {})
}

startElectron()
watchMainProcess()
