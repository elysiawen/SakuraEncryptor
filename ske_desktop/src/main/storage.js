/**
 * Encrypted-at-rest storage for cloud profiles and app settings.
 *
 * Two separate concerns, deliberately:
 *
 *  - `vault.json` holds cloud profiles. Server, username and base path are
 *    plaintext (they are not secrets and the UI needs them before unlock); the
 *    two passwords are sealed with the OS keychain via `safeStorage`, which is
 *    the desktop equivalent of Android's Keystore.
 *  - `settings.json` holds UI preferences. Nothing secret there, so it stays
 *    readable — that keeps a corrupt keychain from bricking the app's layout.
 *
 * Writes go through a temp file + rename so a crash mid-save cannot leave a
 * half-written vault behind, which would lose every profile at once.
 */
import { app, safeStorage } from 'electron'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'

const VAULT_VERSION = 1

export const DEFAULT_SETTINGS = {
  theme: 'dark',
  // Only previews reach the disk, and only encrypted. Decrypted blocks stay in
  // memory with a fixed bound, so they have no setting on purpose.
  cache: {
    thumbEnabled: true,
    thumbLimitMB: 128,
  },
  playback: {
    seekStep: 5,
    volume: 1,
    autoPlayNext: true,
    /** Empty means "find mpv/VLC/PotPlayer yourself", which is the usual case. */
    externalPlayer: '',
  },
  encrypt: {
    outputDir: '',
  },
  download: {
    /** Empty means "ask every time": there is no sane cross-platform default. */
    dir: '',
  },
  window: {
    width: 1440,
    height: 900,
    maximized: false,
    /** Closing the window keeps the app alive in the tray when true. */
    closeToTray: false,
  },
}

function userDataPath(name) {
  return path.join(app.getPath('userData'), name)
}

async function readJson(filePath, fallback) {
  try {
    const raw = await fs.readFile(filePath, 'utf8')
    return JSON.parse(raw)
  } catch (err) {
    if (err.code === 'ENOENT') return fallback
    // A corrupted file must not take the app down: keep a copy for support and
    // start from defaults, which the UI can explain.
    console.error(`[storage] cannot read ${filePath}:`, err.message)
    await fs.rename(filePath, `${filePath}.corrupt-${Date.now()}`).catch(() => {})
    return fallback
  }
}

async function writeJson(filePath, value) {
  await fs.mkdir(path.dirname(filePath), { recursive: true })
  const tmp = `${filePath}.tmp`
  await fs.writeFile(tmp, JSON.stringify(value, null, 2), 'utf8')
  await fs.rename(tmp, filePath)
}

/** Seal a secret with the OS keychain. Returns null for an empty value. */
function seal(plain) {
  if (!plain) return null
  if (safeStorage.isEncryptionAvailable()) {
    return { enc: safeStorage.encryptString(plain).toString('base64') }
  }
  // Linux without a keyring (or a headless session): keep the feature usable
  // instead of refusing to save, but mark it so the UI can warn honestly.
  return { plain: Buffer.from(plain, 'utf8').toString('base64'), insecure: true }
}

function unseal(record) {
  if (!record) return ''
  try {
    if (record.enc) return safeStorage.decryptString(Buffer.from(record.enc, 'base64'))
    if (record.plain) return Buffer.from(record.plain, 'base64').toString('utf8')
  } catch (err) {
    console.error('[storage] cannot unseal secret:', err.message)
  }
  return ''
}

function newId() {
  return crypto.randomBytes(8).toString('hex')
}

/** Strip sealed material before anything crosses IPC into the renderer. */
function publicProfile(profile) {
  if (!profile) return null
  return {
    id: profile.id,
    name: profile.name,
    server: profile.server || '',
    username: profile.username || '',
    basePath: profile.basePath || '',
    remember: !!profile.remember,
    hasPassword: !!profile.secrets?.password,
    hasSkePassword: !!profile.secrets?.skePassword,
    insecure: !!(profile.secrets?.password?.insecure || profile.secrets?.skePassword?.insecure),
  }
}

export class Storage {
  constructor() {
    this.vaultPath = userDataPath('vault.json')
    this.settingsPath = userDataPath('settings.json')
    this.vault = { version: VAULT_VERSION, profiles: [], activeId: null }
    this.settings = structuredClone(DEFAULT_SETTINGS)
    this.loaded = false
  }

  async load() {
    const vault = await readJson(this.vaultPath, null)
    if (vault && Array.isArray(vault.profiles)) {
      this.vault = {
        version: vault.version ?? VAULT_VERSION,
        profiles: vault.profiles,
        activeId: vault.activeId ?? null,
      }
    }

    const settings = await readJson(this.settingsPath, null)
    if (settings) {
      // Shallow-merge per section so a new default key is not lost when the
      // user already has an older settings file.
      this.settings = {
        ...DEFAULT_SETTINGS,
        ...settings,
        cache: { ...DEFAULT_SETTINGS.cache, ...(settings.cache || {}) },
        playback: { ...DEFAULT_SETTINGS.playback, ...(settings.playback || {}) },
        encrypt: { ...DEFAULT_SETTINGS.encrypt, ...(settings.encrypt || {}) },
        download: { ...DEFAULT_SETTINGS.download, ...(settings.download || {}) },
        window: { ...DEFAULT_SETTINGS.window, ...(settings.window || {}) },
      }
    }

    this.loaded = true
    return this
  }

  async saveVault() {
    await writeJson(this.vaultPath, this.vault)
  }

  async saveSettings() {
    await writeJson(this.settingsPath, this.settings)
  }

  // --- profiles ------------------------------------------------------------

  listProfiles() {
    return {
      profiles: this.vault.profiles.map(publicProfile),
      activeId: this.vault.activeId,
    }
  }

  /** Secrets for internal use (connecting to AList, unlocking media). */
  getSecrets(id) {
    const profile = this.vault.profiles.find((p) => p.id === id)
    if (!profile) return null
    return {
      password: unseal(profile.secrets?.password),
      skePassword: unseal(profile.secrets?.skePassword),
    }
  }

  async saveProfile(input) {
    const name = String(input?.name || '').trim()
    const server = String(input?.server || '').trim().replace(/\/+$/, '')
    if (!name) throw new Error('配置名称不能为空')
    if (!server) throw new Error('服务器地址不能为空')

    const existing = input.id ? this.vault.profiles.find((p) => p.id === input.id) : null
    const profile = existing || {
      id: newId(),
      secrets: {},
    }

    profile.name = name
    profile.server = server
    profile.username = String(input.username || '')
    profile.basePath = String(input.basePath || '')
    profile.remember = input.remember !== false
    profile.secrets = profile.secrets || {}

    // An empty password field means "keep what is stored": the dialog never
    // shows existing passwords, so blank cannot be distinguished from "clear".
    if (typeof input.password === 'string' && input.password.length > 0) {
      profile.secrets.password = seal(input.password)
    }
    if (typeof input.skePassword === 'string' && input.skePassword.length > 0) {
      profile.secrets.skePassword = seal(input.skePassword)
    }
    if (input.clearPassword) delete profile.secrets.password
    if (input.clearSkePassword) delete profile.secrets.skePassword

    if (!existing) this.vault.profiles.push(profile)
    if (!this.vault.activeId) this.vault.activeId = profile.id

    await this.saveVault()
    return publicProfile(profile)
  }

  async deleteProfile(id) {
    const before = this.vault.profiles.length
    this.vault.profiles = this.vault.profiles.filter((p) => p.id !== id)
    if (this.vault.profiles.length === before) return false
    if (this.vault.activeId === id) {
      this.vault.activeId = this.vault.profiles[0]?.id ?? null
    }
    await this.saveVault()
    return true
  }

  async setActiveProfile(id) {
    if (id && !this.vault.profiles.some((p) => p.id === id)) {
      throw new Error('配置不存在')
    }
    this.vault.activeId = id || null
    await this.saveVault()
    return this.vault.activeId
  }

  // --- settings ------------------------------------------------------------

  patchSettings(patch = {}) {
    for (const [key, value] of Object.entries(patch)) {
      if (!(key in DEFAULT_SETTINGS)) continue
      if (value && typeof value === 'object' && !Array.isArray(value)) {
        this.settings[key] = { ...this.settings[key], ...value }
      } else {
        this.settings[key] = value
      }
    }
    return this.saveSettings().then(() => this.settings)
  }
}
