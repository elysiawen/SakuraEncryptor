/**
 * App launch lock.
 *
 * Scope, stated plainly: this keeps a passer-by out of an unlocked machine. It
 * is not disk encryption — someone with the user's file system can read
 * `security.json` and attack the password hash offline. `scrypt` with a
 * per-install salt makes that expensive enough to be pointless for the casual
 * case, which is what a launch lock is for.
 *
 * Two factors are possible — a password and a TOTP token — and they combine the
 * same way the Android client combines them: with `match: 'all'` every
 * configured factor must pass, with `match: 'any'` one is enough. Which factors
 * are demanded is implied by what is configured; there is no separate "ticked"
 * list that could disagree with the bindings.
 *
 * The TOTP secret is sealed with `safeStorage` (DPAPI on Windows, Keychain on
 * macOS, libsecret on Linux) instead of being encrypted with the launch
 * password: with `match: 'any'` the app has to verify a code *without* being
 * given the password first.
 */
import { app, safeStorage } from 'electron'
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import { decodeSecret, encodeBase32, generateSecret, otpauthUri, verifyCode } from './totp.js'

const SCRYPT = { N: 16384, r: 8, p: 1, keylen: 64 }
const RECORD_VERSION = 2

/**
 * Shortest password the lock accepts.
 *
 * Enforced here rather than only in the form: the renderer must not be the last
 * line of defence, and an existing shorter password keeps unlocking (the
 * minimum applies when one is set, not when one is checked).
 */
export const MIN_PASSWORD_LENGTH = 6

/** How several configured factors combine. Mirrors `AppLockMatch` on Android. */
export const MATCH_ALL = 'all'
export const MATCH_ANY = 'any'

const FACTOR_LABELS = { password: '密码', totp: '验证码' }

export class Security {
  constructor() {
    this.filePath = path.join(app.getPath('userData'), 'security.json')
    this.record = { version: RECORD_VERSION, password: null, totp: null, match: MATCH_ALL }
    this.failedAttempts = 0
    this.lockedUntil = 0
    this.unlocked = false
  }

  async load() {
    try {
      const raw = await fs.readFile(this.filePath, 'utf8')
      const parsed = JSON.parse(raw)
      if (parsed && typeof parsed === 'object') {
        this.record = {
          version: RECORD_VERSION,
          password: null,
          totp: null,
          match: MATCH_ALL,
          ...parsed,
        }
      }
    } catch (err) {
      if (err.code !== 'ENOENT') {
        console.error('[security] cannot read lock file:', err.message)
      }
    }
    // Nothing configured means the app opens straight into the UI.
    this.unlocked = !this.isEnabled()
    return this
  }

  async save() {
    await fs.mkdir(path.dirname(this.filePath), { recursive: true })
    const tmp = `${this.filePath}.tmp`
    await fs.writeFile(tmp, JSON.stringify(this.record, null, 2), 'utf8')
    await fs.rename(tmp, this.filePath)
  }

  /** Configured factors, in the order the lock screen should ask for them. */
  factors() {
    const list = []
    if (this.record.password) list.push('password')
    if (this.record.totp) list.push('totp')
    return list
  }

  get matchMode() {
    return this.record.match === MATCH_ANY ? MATCH_ANY : MATCH_ALL
  }

  isEnabled() {
    return !!(this.record.password || this.record.totp)
  }

  state() {
    return {
      enabled: this.isEnabled(),
      unlocked: this.unlocked,
      hasPassword: !!this.record.password,
      hasTotp: !!this.record.totp,
      factors: this.factors(),
      match: this.matchMode,
      lockedUntil: this.lockedUntil > Date.now() ? this.lockedUntil : 0,
    }
  }

  #hash(password, salt) {
    return crypto.scryptSync(Buffer.from(password, 'utf8'), salt, SCRYPT.keylen, {
      N: SCRYPT.N,
      r: SCRYPT.r,
      p: SCRYPT.p,
    })
  }

  /**
   * Set or change the launch password.
   *
   * Changing requires the current password: otherwise anyone at an unlocked
   * machine could silently replace the lock with their own. Adding a password
   * next to an existing TOTP token needs no password to compare against — the
   * window is already unlocked, and the token stays in the set.
   */
  async setPassword(password, currentPassword) {
    const value = String(password || '')
    if (value.length < MIN_PASSWORD_LENGTH) {
      throw new Error(`启动密码至少 ${MIN_PASSWORD_LENGTH} 位`)
    }
    if (this.record.password && !this.verify(currentPassword)) {
      throw new Error('当前密码不正确')
    }
    const salt = crypto.randomBytes(16)
    this.record.password = {
      salt: salt.toString('base64'),
      hash: this.#hash(value, salt).toString('base64'),
      ...SCRYPT,
    }
    await this.save()
    this.unlocked = true
    return this.state()
  }

  verify(password) {
    const stored = this.record.password
    if (!stored) return true
    try {
      const salt = Buffer.from(stored.salt, 'base64')
      const expected = Buffer.from(stored.hash, 'base64')
      const actual = this.#hash(String(password || ''), salt)
      return actual.length === expected.length && crypto.timingSafeEqual(actual, expected)
    } catch {
      return false
    }
  }

  // --- TOTP ----------------------------------------------------------------

  #canSeal() {
    try {
      return safeStorage.isEncryptionAvailable()
    } catch {
      return false
    }
  }

  /** @return the decoded shared secret, or null when none is bound/readable. */
  #totpSecret() {
    const sealed = this.record.totp?.secret
    if (!sealed || !this.#canSeal()) return null
    try {
      return decodeSecret(safeStorage.decryptString(Buffer.from(sealed, 'base64')))
    } catch {
      // A secret sealed by another OS user (or a broken keyring) can never be
      // proven; the caller treats that as a failed factor, not as a crash.
      return null
    }
  }

  /** True when a code is valid for the current 30-second step. */
  verifyTotp(code) {
    const secret = this.#totpSecret()
    return secret ? verifyCode(secret, code) : false
  }

  /**
   * A fresh secret to hand to an authenticator app.
   *
   * Nothing is stored yet: the user has to prove they imported it first, so a
   * mis-copied key never becomes a factor nobody can pass.
   */
  beginTotp() {
    if (!this.#canSeal()) {
      throw new Error('系统未提供安全存储（密钥环或凭据管理器），无法绑定 TOTP')
    }
    const secret = encodeBase32(generateSecret())
    return { secret, uri: otpauthUri(secret) }
  }

  /**
   * Finish binding, after checking one code generated from `secret`.
   *
   * The secret comes back from the renderer rather than being held here: the
   * settings page also lets the user paste a key they already have, and both
   * paths must be verifiable before anything is written to disk.
   */
  async confirmTotp(code, secretInput) {
    const secret = decodeSecret(secretInput)
    if (!secret) throw new Error('密钥格式不正确，应为 Base32 字符串或 otpauth 链接')
    if (!verifyCode(secret, code)) throw new Error('验证码不正确或已过期')
    if (!this.#canSeal()) {
      throw new Error('系统未提供安全存储（密钥环或凭据管理器），无法绑定 TOTP')
    }

    this.record.totp = {
      secret: safeStorage.encryptString(encodeBase32(secret)).toString('base64'),
      createdAt: new Date().toISOString(),
    }
    // A token on its own is a gate; two factors default to the stricter mode,
    // which is what "2FA" is expected to mean.
    if (!this.record.match) this.record.match = MATCH_ALL
    await this.save()
    return this.state()
  }

  async unbindTotp() {
    this.record.totp = null
    await this.save()
    // Nothing left to prove: the gate must not stay up with no way through it.
    if (!this.isEnabled()) this.unlocked = true
    return this.state()
  }

  async setMatch(match) {
    this.record.match = String(match) === MATCH_ANY ? MATCH_ANY : MATCH_ALL
    await this.save()
    return this.state()
  }

  /** Turn the whole lock off. Every configured factor has to be proven. */
  async disable({ password = '', totp = '' } = {}) {
    if (this.record.password && !this.verify(password)) throw new Error('密码不正确')
    if (this.record.totp && !this.verifyTotp(totp)) throw new Error('验证码不正确或已过期')

    this.record.password = null
    this.record.totp = null
    this.record.match = MATCH_ALL
    await this.save()
    this.unlocked = true
    return this.state()
  }

  // --- gate ----------------------------------------------------------------

  /**
   * @param {{ password?: string, totp?: string } | string} input
   *
   * A bare string is still accepted as a password, so an older caller cannot
   * silently turn into "no credentials supplied".
   */
  async unlock(input = {}) {
    const { password = '', totp = '' } = typeof input === 'string' ? { password: input } : input || {}
    if (!this.isEnabled()) {
      this.unlocked = true
      return this.state()
    }
    if (Date.now() < this.lockedUntil) {
      throw new Error('尝试过于频繁，请稍后再试')
    }

    const required = this.factors()
    const passed = new Set()
    if (required.includes('password') && password && this.verify(password)) passed.add('password')
    if (required.includes('totp') && totp && this.verifyTotp(totp)) passed.add('totp')

    const satisfied =
      this.matchMode === MATCH_ANY
        ? required.some((factor) => passed.has(factor))
        : required.every((factor) => passed.has(factor))

    if (!satisfied) {
      this.failedAttempts += 1
      // Back off after a few misses: cheap to implement, and it turns an
      // unattended machine into a bad target for guessing.
      if (this.failedAttempts >= 5) {
        this.lockedUntil = Date.now() + 30_000
        this.failedAttempts = 0
      }
      throw new Error(this.#failureMessage(required, passed))
    }

    this.failedAttempts = 0
    this.unlocked = true
    return this.state()
  }

  #failureMessage(required, passed) {
    const missing = required.filter((factor) => !passed.has(factor))
    const problems = missing.map((factor) =>
      factor === 'totp' ? '验证码不正确或已过期' : '密码不正确',
    )
    return problems.join('；') || '验证不通过'
  }

  lock() {
    if (!this.isEnabled()) return this.state()
    this.unlocked = false
    return this.state()
  }
}
