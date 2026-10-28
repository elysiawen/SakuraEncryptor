/**
 * Pins the TOTP implementation to the RFC 6238 vectors.
 *
 * The gate is the only thing between a locked app and the library, and a secret
 * that produces different codes than every authenticator app would lock the
 * user out for good. `codeAt` is therefore checked against the published
 * values, not against itself.
 */
import assert from 'node:assert/strict'
import {
  codeAt,
  decodeBase32,
  decodeSecret,
  encodeBase32,
  generateSecret,
  otpauthUri,
  TOTP_DIGITS,
  verifyCode,
} from '../src/main/totp.js'

// RFC 6238 appendix B, HMAC-SHA1: the 8-digit values truncated to our 6.
const SECRET = Buffer.from('12345678901234567890', 'ascii')
const VECTORS = [
  [59_000, '287082'],
  [1_111_111_109_000, '081804'],
  [1_111_111_111_000, '050471'],
  [1_234_567_890_000, '005924'],
  [2_000_000_000_000, '279037'],
  [20_000_000_000_000, '353130'],
]

for (const [millis, expected] of VECTORS) {
  assert.equal(codeAt(SECRET, millis), expected, `RFC vector at ${millis}`)
}

// Base32 is the only form a user can type into an authenticator by hand.
const base32 = encodeBase32(SECRET)
assert.equal(base32, 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ')
assert.deepEqual(decodeBase32(base32), SECRET)
assert.deepEqual(decodeBase32('gezd gnbv gy3t qojq gezd gnbv gy3t qojq'), SECRET)
assert.deepEqual(decodeBase32('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ=='), SECRET)
assert.equal(decodeBase32('GEZD1NBV'), null)

// What an authenticator app offers to copy can be either form.
assert.deepEqual(decodeSecret(base32), SECRET)
assert.deepEqual(decodeSecret(otpauthUri(base32)), SECRET)
assert.deepEqual(
  decodeSecret('otpauth://totp/Sakura%20Encryptor:app?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=x'),
  SECRET,
)
assert.equal(decodeSecret('not base32!'), null)

// A generated secret is usable, and only the current step passes.
const fresh = generateSecret()
assert.equal(fresh.length, 20)
const roundTrip = decodeBase32(encodeBase32(fresh))
assert.deepEqual(roundTrip, fresh)

const now = 1_700_000_000_000
const current = codeAt(fresh, now)
assert.equal(current.length, TOTP_DIGITS)
assert.ok(verifyCode(fresh, current, now))
assert.ok(verifyCode(fresh, ` ${current} `, now))
assert.ok(!verifyCode(fresh, codeAt(fresh, now - 30_000), now), 'previous step must fail')
assert.ok(!verifyCode(fresh, codeAt(fresh, now + 30_000), now), 'next step must fail')
assert.ok(!verifyCode(fresh, '12345', now), 'short input must fail')

console.log('totp ok')
