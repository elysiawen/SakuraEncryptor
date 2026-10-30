/**
 * Finding and launching a desktop player for a decrypted stream.
 *
 * Chromium cannot open avi, wmv, flv, ts or older codecs, and the media router
 * exists precisely so a player that can open them can read the very same
 * `127.0.0.1` URL — seeking included, plaintext never on disk.
 *
 * Finding one is the hard part: a player is rarely where a guess puts it. Three
 * passes are made once per launch — the usual install layouts, a shallow scan of
 * the program directories, and (on Windows) the shell's own registration — so a
 * PotPlayer in `D:\Tools` or `C:\Program Files\PotPlayer` is still found.
 */
import { execFile, spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { promisify } from 'node:util'

const run = promisify(execFile)

const IS_WIN = process.platform === 'win32'
const IS_MAC = process.platform === 'darwin'

/** `[display name, executable]` in the order the menu should offer them. */
const BINARIES = IS_WIN
  ? [
      ['PotPlayer', 'PotPlayerMini64.exe'],
      ['PotPlayer', 'PotPlayerMini.exe'],
      ['VLC', 'vlc.exe'],
      ['mpv', 'mpv.exe'],
      ['MPC-HC', 'mpc-hc64.exe'],
      ['MPC-HC', 'mpc-hc.exe'],
      ['MPC-BE', 'mpc-be64.exe'],
      ['MPC-BE', 'mpc-be.exe'],
      ['ffplay', 'ffplay.exe'],
    ]
  : IS_MAC
    ? [
        ['VLC', 'VLC'],
        ['IINA', 'iina-cli'],
        ['mpv', 'mpv'],
        ['ffplay', 'ffplay'],
      ]
    : [
        ['mpv', 'mpv'],
        ['VLC', 'vlc'],
        ['Celluloid', 'celluloid'],
        ['Totem', 'totem'],
        ['ffplay', 'ffplay'],
      ]

function isFile(candidate) {
  if (!candidate) return false
  try {
    return fs.statSync(candidate).isFile()
  } catch {
    return false
  }
}

/** Windows installers put the same player in one of a handful of places. */
const WIN_LAYOUTS = [
  ['VideoLAN', 'VLC', 'vlc.exe'],
  ['PotPlayer', 'PotPlayerMini64.exe'],
  ['PotPlayer', 'PotPlayerMini.exe'],
  ['DAUM', 'PotPlayer', 'PotPlayerMini64.exe'],
  ['DAUM', 'PotPlayer', 'PotPlayerMini.exe'],
  ['mpv', 'mpv.exe'],
  ['mpv', 'mpv-x86_64', 'mpv.exe'],
  ['MPC-HC', 'mpc-hc64.exe'],
  ['MPC-HC', 'mpc-hc.exe'],
  ['MPC-BE x64', 'mpc-be64.exe'],
  ['MPC-BE', 'mpc-be64.exe'],
  ['K-Lite Codec Pack', 'MPC-HC64', 'mpc-hc64.exe'],
  ['ffmpeg', 'bin', 'ffplay.exe'],
]

function programRoots() {
  if (IS_WIN) {
    return [
      process.env.ProgramFiles,
      process.env['ProgramFiles(x86)'],
      process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Programs'),
      process.env.LOCALAPPDATA,
    ].filter(Boolean)
  }
  return IS_MAC ? ['/Applications'] : ['/usr/bin', '/usr/local/bin', '/opt/homebrew/bin', '/snap/bin']
}

function installCandidates() {
  if (!IS_WIN) {
    return IS_MAC
      ? [
          '/Applications/VLC.app/Contents/MacOS/VLC',
          '/Applications/IINA.app/Contents/MacOS/iina-cli',
          '/Applications/mpv.app/Contents/MacOS/mpv',
          '/opt/homebrew/bin/mpv',
          '/usr/local/bin/mpv',
        ]
      : ['/usr/bin/mpv', '/usr/bin/vlc', '/usr/bin/celluloid', '/usr/bin/totem']
  }

  const found = []
  for (const root of programRoots()) {
    for (const layout of WIN_LAYOUTS) found.push(path.join(root, ...layout))
  }
  return found
}

const WANTED_NAMES = new Set(BINARIES.map(([, exe]) => exe.toLowerCase()))

/** Vendor folders worth descending into when the player is not at the top. */
const VENDOR_DIR = /(potplayer|daum|vlc|mpv|mpc|k-lite|ffmpeg|video)/i

/** One `readdir`: matching executables here, subdirectories to look at next. */
function readDir(dir) {
  let entries
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true })
  } catch {
    return { hits: [], subdirs: [] }
  }

  const hits = []
  const subdirs = []
  for (const entry of entries) {
    if (entry.isDirectory()) subdirs.push(entry.name)
    else if (WANTED_NAMES.has(entry.name.toLowerCase())) hits.push(path.join(dir, entry.name))
  }
  return { hits, subdirs }
}

/**
 * Two levels of the program directories, by executable name.
 *
 * A player installed where nobody predicted still has its exe name. The second
 * level is only entered for vendor folders that look relevant, so a right-click
 * does not pay for a `readdir` of every Office and Windows SDK subdirectory.
 */
function scanInstallDirs() {
  const found = []

  for (const root of programRoots()) {
    let top
    try {
      top = fs.readdirSync(root, { withFileTypes: true })
    } catch {
      continue
    }

    for (const entry of top) {
      if (!entry.isDirectory()) continue

      const base = path.join(root, entry.name)
      const first = readDir(base)
      found.push(...first.hits)

      // `Vendor\Product\player.exe`: only worth descending when the vendor is
      // one of ours, since the direct hit above already covered the easy case.
      if (first.hits.length || !VENDOR_DIR.test(entry.name)) continue
      for (const sub of first.subdirs) {
        found.push(...readDir(path.join(base, sub)).hits)
      }
    }
  }

  return found
}

/**
 * What Windows itself remembers about the installed players.
 *
 * `HKCR\Applications\<exe>\shell\open\command` holds the absolute path of every
 * executable the shell has been asked to open, which is exactly the set a user
 * who has ever double-clicked a video has. `App Paths` covers the rest.
 */
async function registryCandidates() {
  if (!IS_WIN) return []

  const exes = [...new Set(BINARIES.map(([, exe]) => exe))]
  const keys = []
  for (const exe of exes) {
    keys.push(`HKCR\\Applications\\${exe}\\shell\\open\\command`)
    keys.push(`HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\${exe}`)
  }

  // Parallel on purpose: each query launches `reg.exe`, and doing eighteen of
  // them in a row is a visible pause before the first menu opens.
  const found = await Promise.all(
    keys.map(async (key) => {
      try {
        const { stdout } = await run('reg', ['query', key, '/ve'], {
          timeout: 4000,
          windowsHide: true,
        })
        const match = stdout.match(/[A-Za-z]:\\[^"\r\n]+?\.exe/i)
        return match ? match[0] : ''
      } catch {
        // The key does not exist: the player was never registered.
        return ''
      }
    }),
  )

  return found.filter(Boolean)
}

function onPath(exe) {
  const dirs = String(process.env.PATH || '').split(path.delimiter)
  for (const dir of dirs) {
    if (!dir) continue
    const candidate = path.join(dir, exe)
    if (isFile(candidate)) return candidate
  }
  return ''
}

/**
 * Identity of a binary, for de-duplication.
 *
 * The same file arrives spelled differently — `D:\PotPlayer\\mpv.exe`, a
 * different letter case, a symlink — and the menu must not list it twice.
 */
function binaryKey(file) {
  try {
    return fs.realpathSync.native(file).toLowerCase()
  } catch {
    return path.resolve(file).toLowerCase()
  }
}

function labelFor(binary) {
  const name = path.basename(binary).toLowerCase()
  const known = BINARIES.find(([, exe]) => exe.toLowerCase() === name)
  return known ? known[0] : path.basename(binary, path.extname(binary))
}

let detected = null

async function detect() {
  if (detected) return detected

  const candidates = [
    ...installCandidates(),
    ...scanInstallDirs(),
    ...(await registryCandidates()),
    ...BINARIES.map(([, exe]) => onPath(exe)),
  ].filter(isFile)

  const seenPaths = new Set()
  const seenNames = new Set()
  const players = []

  for (const candidate of candidates) {
    const key = binaryKey(candidate)
    const name = labelFor(candidate)
    // One entry per player: a machine with both PotPlayer builds should not
    // offer the same app twice.
    if (seenPaths.has(key) || seenNames.has(name)) continue
    seenPaths.add(key)
    seenNames.add(name)
    players.push({ name, path: candidate })
  }

  detected = players
  return players
}

/**
 * Every player this machine can launch, best first.
 *
 * A configured path is promoted to the front and marked, but the detected ones
 * stay in the list: picking another one for a single file is the point of the
 * submenu.
 */
export async function listPlayers(preferred = '') {
  const players = await detect()
  const configured = String(preferred || '').trim()
  if (!configured || !isFile(configured)) return players

  const wantedKey = binaryKey(configured)
  const rest = players.filter((item) => binaryKey(item.path) !== wantedKey)
  return [{ name: labelFor(configured), path: configured, preferred: true }, ...rest]
}

/**
 * Open [url] in [exe], or in the first player found.
 *
 * `spawn` rather than `shell.openExternal`: the latter hands an http URL to the
 * default *browser*, which is not what "play this video" means. No shell is
 * involved, so the URL travels as a plain argument.
 */
export async function playExternal(url, exe = '', { strict = false } = {}) {
  const players = await listPlayers()
  let target = players[0]?.path || ''

  const wanted = String(exe || '').trim()
  if (wanted) {
    if (isFile(wanted)) target = wanted
    // A path the user picked by hand is an instruction, not a hint: a stale one
    // must not silently become "some other player".
    else if (strict) throw new Error('这个播放器已经不存在了，请重新选择')
  }

  if (!target) {
    throw new Error('没有找到外部播放器，请在设置里指定，或用「复制播放链接」粘到播放器里')
  }

  await new Promise((resolve, reject) => {
    // Detached: the player outlives a window reload. `windowsHide` only stops a
    // console flash for CLI players; a GUI player ignores it.
    const child = spawn(target, [url], { detached: true, stdio: 'ignore', windowsHide: true })
    child.once('error', reject)
    child.once('spawn', () => {
      child.unref()
      resolve()
    })
  })

  return { player: labelFor(target), path: target }
}
