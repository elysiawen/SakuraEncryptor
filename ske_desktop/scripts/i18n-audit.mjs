/**
 * Translation audit.
 *
 *   node scripts/i18n-audit.mjs           # report; fails on structural errors
 *   node scripts/i18n-audit.mjs --strict  # also fail on any remaining Chinese
 *
 * The library reports a missing key at runtime, in development, once per call
 * site. That is useful while writing a screen and useless for the two questions
 * this script answers:
 *
 *   1. Do all locales define exactly the same keys? (a key added to zh-CN and
 *      forgotten in en-US renders as the raw key at runtime)
 *   2. Which visible strings are still hardcoded Chinese? (the migration's
 *      remaining work, and the thing a reviewer cannot see by reading a diff)
 *
 * Key extraction is regex-based on purpose: a real parser would need to know
 * about `<script setup>` blocks, JSX-ish templates and string concatenation,
 * and the conventions here are narrow enough (`$t('a.b')`, `t('a.b')`,
 * `translate(ctx, 'a.b')`) that a parser would be a second project.
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(here, '..')
const srcDir = path.join(rootDir, 'src')
const localesDir = path.join(srcDir, 'shared/i18n/locales')

const strict = process.argv.includes('--strict')

const SOURCE_EXTENSIONS = new Set(['.js', '.cjs', '.mjs', '.vue', '.html'])
const CJK = /[\u3400-\u4dbf\u4e00-\u9fff\u3040-\u30ff]/

/** `x_one` / `x_other` are plural variants of `x`, not keys of their own. */
function baseKey(key) {
  return key.replace(/_(zero|one|two|few|many|other)$/, '')
}

function flatten(value, prefix = '', out = new Map()) {
  for (const [key, entry] of Object.entries(value)) {
    const full = prefix ? `${prefix}.${key}` : key
    if (entry && typeof entry === 'object' && !Array.isArray(entry)) {
      flatten(entry, full, out)
    } else {
      out.set(full, entry)
    }
  }
  return out
}

/** Translate calls in a source line, so the report can point at them. */
function collectReferences(text) {
  const found = []
  // `(?<![\w$])` keeps `format('x')` and `parseInt('x')` from looking like calls.
  const patterns = [
    // $t('a.b'), t('a.b'), te('a.b'), i18n.global.t('a.b')
    /(?<![\w$])(?:\$te|\$t|te|t)\(\s*'([A-Za-z0-9_$.]+)'/g,
    /(?<![\w$])(?:\$te|\$t|te|t)\(\s*"([A-Za-z0-9_$.]+)"/g,
    // translate(context, 'a.b')
    /translate\(\s*[^,()]+,\s*'([A-Za-z0-9_$.]+)'/g,
    /translate\(\s*[^,()]+,\s*"([A-Za-z0-9_$.]+)"/g,
  ]
  for (const pattern of patterns) {
    for (const match of text.matchAll(pattern)) found.push(match[1])
  }
  return found
}

/**
 * Keys carried as data rather than passed to a call: a nav entry's `labelKey`,
 * a route's `meta.title`, a lookup table like `{ stopped: 'embedded.statusStopped' }`
 * that a function indexes into.
 *
 * These are only counted when their first segment is a real namespace. Without
 * that filter `path: 'stage.html'` looks exactly like a translation key.
 */
function collectDataReferences(text) {
  const found = []
  const patterns = [
    /\w+:\s*'([A-Za-z0-9_$]+\.[A-Za-z0-9_$.]+)'/g,
    /\w+:\s*"([A-Za-z0-9_$]+\.[A-Za-z0-9_$.]+)"/g,
  ]
  for (const pattern of patterns) {
    for (const match of text.matchAll(pattern)) found.push(match[1])
  }
  return found
}

/** Drop the parts of a line that a user never sees, so the report stays honest. */
function visibleText(line) {
  return line
    .replace(/<!--.*?-->/g, '')
    .replace(/\/\*.*?\*\//g, '')
    .replace(/\/\/.*$/, '')
    .replace(/^\s*\*.*$/, '')
}

function* walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'locales') continue
      yield* walk(full)
    } else if (SOURCE_EXTENSIONS.has(path.extname(entry.name))) {
      yield full
    }
  }
}

async function loadLocaleTables() {
  const tables = new Map()
  for (const entry of fs.readdirSync(localesDir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue
    const file = path.join(localesDir, entry.name, 'index.js')
    if (!fs.existsSync(file)) {
      tables.set(entry.name, null)
      continue
    }
    const module = await import(pathToFileURL(file).href)
    tables.set(entry.name, flatten(module.default || {}))
  }
  return tables
}

const tables = await loadLocaleTables()
const codes = [...tables.keys()].sort()
// The Chinese table is the source of truth: it is the language the screens were
// written in, so a key that exists only elsewhere is the anomaly.
const reference = codes.includes('zh-CN') ? 'zh-CN' : codes[0]

let failures = 0
function fail(message) {
  failures += 1
  console.error(`  FAIL ${message}`)
}

console.log('i18n audit')
for (const code of codes) {
  const table = tables.get(code)
  if (!table) {
    fail(`${code}: missing locales/${code}/index.js`)
    continue
  }
  console.log(`  ${code.padEnd(6)} ${table.size} keys`)
}

// --- 1. locale tables agree ------------------------------------------------

const referenceTable = tables.get(reference) || new Map()
for (const code of codes) {
  const table = tables.get(code)
  if (!table || code === reference) continue
  const missing = [...referenceTable.keys()].filter((key) => !table.has(key))
  const extra = [...table.keys()].filter((key) => !referenceTable.has(key))
  if (missing.length) {
    fail(`${code}: ${missing.length} key(s) missing, e.g. ${missing.slice(0, 5).join(', ')}`)
  }
  if (extra.length) {
    fail(`${code}: ${extra.length} key(s) not in ${reference}, e.g. ${extra.slice(0, 5).join(', ')}`)
  }
}

// --- 2. every referenced key exists ---------------------------------------

const referenced = new Map()
const chineseLines = new Map()

const namespaces = new Set([...referenceTable.keys()].map((key) => key.split('.')[0]))

for (const file of walk(srcDir)) {
  const relative = path.relative(rootDir, file).replace(/\\/g, '/')
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
  let cjk = 0

  for (const [index, line] of lines.entries()) {
    const here = `${relative}:${index + 1}`
    for (const key of collectReferences(line)) {
      if (!referenced.has(key)) referenced.set(key, here)
    }
    for (const key of collectDataReferences(line)) {
      if (!namespaces.has(key.split('.')[0])) continue
      if (!referenced.has(key)) referenced.set(key, here)
    }
    if (CJK.test(visibleText(line))) cjk += 1
  }

  if (cjk) chineseLines.set(relative, cjk)
}

const unknown = [...referenced.keys()].filter((key) => !referenceTable.has(key) && !referenceTable.has(baseKey(key)))
if (unknown.length) {
  for (const key of unknown) fail(`unknown key ${key} (used at ${referenced.get(key)})`)
}

// --- 3. keys nothing uses --------------------------------------------------

const usedBases = new Set([...referenced.keys()].map(baseKey))
const unused = [...referenceTable.keys()].filter((key) => !usedBases.has(baseKey(key)))

// --- report ----------------------------------------------------------------

const totalChinese = [...chineseLines.values()].reduce((sum, count) => sum + count, 0)

console.log(`  referenced keys: ${referenced.size} unique`)
console.log('')
console.log(`unknown keys:            ${unknown.length}`)
console.log(`unused keys:             ${unused.length}`)
console.log(`remaining Chinese lines: ${totalChinese} in ${chineseLines.size} file(s)`)

if (unused.length) {
  console.log('')
  console.log('unused (remove or wire up):')
  for (const key of unused.slice(0, 20)) console.log(`  ${key}`)
  if (unused.length > 20) console.log(`  … ${unused.length - 20} more`)
}

if (chineseLines.size) {
  console.log('')
  console.log('largest remaining files:')
  const top = [...chineseLines.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12)
  for (const [file, count] of top) console.log(`  ${file.padEnd(56)} ${count}`)
}

if (strict && chineseLines.size) {
  fail(`${totalChinese} hardcoded Chinese line(s) remain (--strict)`)
}

console.log('')
if (failures) {
  console.error(`i18n audit: ${failures} problem(s)`)
  process.exit(1)
}
console.log('i18n audit: ok')
