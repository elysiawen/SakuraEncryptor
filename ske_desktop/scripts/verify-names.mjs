/**
 * Checks the Node name cipher against the reference tokens published in
 * docs/SKE-FORMAT.md §10, then round-trips a set of awkward names.
 *
 *   node scripts/verify-names.mjs
 */
import { decryptName, deriveNameKey, encryptName } from '../src/main/ske-names.js'

let checks = 0
let failures = 0

function check(label, actual, expected) {
  checks += 1
  if (actual === expected) return
  failures += 1
  console.error(`FAIL ${label}\n  got:      ${actual}\n  expected: ${expected}`)
}

// Reference vectors: password `sakura-pass`, produced by the Python reference.
const key = deriveNameKey('sakura-pass')
const vectors = [
  ['hello.mp4', '-WDapQ3GsjxJfy6fEt-tJuO1fQSTvy6RgA'],
  ['视频 测试.mkv', 'eaIwIMB5_6rIsHX-E9ylPnPDHCs2SH8ac7ZOiZ7zCk_b'],
  ['樱花', 'd60HIehZAmgeaRpqWbNNYSvR13deXw'],
  ['a', '8F07rQb78nknIdjJwUquKi0'],
]

for (const [plain, token] of vectors) {
  check(`加密 ${plain}`, encryptName(plain, key), token)
  check(`解密 ${token}`, decryptName(token, key), plain)
}

// Round-trip anything real-world shaped, including names a naive parser breaks on.
const samples = [
  'a',
  'Movie.2024.2160p.HDR.mkv',
  '带 空格 的名字.mp4',
  'emoji-🌸-file.png',
  'dots...and---dashes.flac',
  'UPPER_lower-123.avi',
  '0',
  'x'.repeat(200),
]

const roundTripKey = deriveNameKey('另一个密码-🌸')
for (const name of samples) {
  check(`往返 ${name.slice(0, 24)}`, decryptName(encryptName(name, roundTripKey), roundTripKey), name)
}

// Determinism is the contract that makes the tree rebuildable.
check('同名同 token', encryptName('same.mp4', key), encryptName('same.mp4', key))

// A wrong key must fail closed rather than return mojibake.
let wrongKeyRejected = false
try {
  decryptName(vectors[0][1], deriveNameKey('wrong-password'))
} catch {
  wrongKeyRejected = true
}
checks += 1
if (!wrongKeyRejected) {
  failures += 1
  console.error('FAIL 错误密码未导致解密失败')
}

console.log(`${checks - failures}/${checks} 项通过`)
process.exit(failures ? 1 : 0)
