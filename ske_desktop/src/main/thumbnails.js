/**
 * Preview generation, matching the rules in docs/SKE-FORMAT.md §9 so a file
 * encrypted here looks the same as one encrypted by the CLI or the Android app.
 *
 * Previews are part of what this client promises, not a nicety: a media file
 * without one stays a placeholder tile for as long as it exists, and only a
 * re-encrypt can fix that. The binaries therefore ship inside the bundle
 * (`ffmpeg-static`, `@ffprobe-installer/ffprobe`); a machine ffmpeg on `PATH`
 * is a development fallback, and a missing pair is an error rather than a
 * silently preview-less container.
 */
import { execFile } from 'node:child_process'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { promisify } from 'node:util'
import ffmpegStatic from 'ffmpeg-static'
import ffprobeStatic from '@ffprobe-installer/ffprobe'

const run = promisify(execFile)

const THUMB_MAX_SIDE = 512
const VIDEO_TIMEOUT_MS = 30_000
const PROBE_TIMEOUT_MS = 15_000
/** How long a failed lookup is trusted before probing the binaries again. */
const TOOL_RETRY_MS = 30_000

const VIDEO_EXT = new Set([
  'mp4', 'm4v', 'mkv', 'mov', 'avi', 'webm', 'ts', 'm2ts', 'flv', 'wmv', 'mpg', 'mpeg', '3gp',
])
const IMAGE_EXT = new Set(['jpg', 'jpeg', 'png', 'webp', 'bmp', 'avif', 'heic', 'heif', 'gif'])

/**
 * A binary inside `app.asar` cannot be executed. electron-builder unpacks these
 * two packages (`build.asarUnpack`), so the path reported by the module points
 * at the archive and has to be redirected to the unpacked copy.
 */
function unpacked(binary) {
  if (typeof binary !== 'string' || !binary) return ''
  return binary.includes('app.asar') ? binary.replace('app.asar', 'app.asar.unpacked') : binary
}

/** The pair that ships with the app. */
const BUNDLED = {
  ffmpeg: unpacked(ffmpegStatic),
  ffprobe: unpacked(ffprobeStatic?.path),
}

/** A machine install, for development and for builds whose bundle is broken. */
const FROM_PATH = { ffmpeg: 'ffmpeg', ffprobe: 'ffprobe' }

let toolCache = null
let toolMissAt = 0

/**
 * Locate a working ffmpeg/ffprobe pair, once.
 *
 * `null` means neither the bundle nor `PATH` has one. Callers that are asked
 * for a preview must treat that as an error: the container would otherwise be
 * written without one, and nothing could add it later.
 */
export async function resolveTools() {
  if (toolCache) return toolCache
  // Do not fork two probes per file while a folder of videos is being
  // encrypted: one failed lookup is trusted for a while.
  if (toolMissAt && Date.now() - toolMissAt < TOOL_RETRY_MS) return null

  for (const candidate of [BUNDLED, FROM_PATH]) {
    if (!candidate.ffmpeg || !candidate.ffprobe) continue
    try {
      await run(candidate.ffmpeg, ['-version'], { timeout: 8000 })
      await run(candidate.ffprobe, ['-version'], { timeout: 8000 })
      toolCache = candidate
      return toolCache
    } catch {
      // Fall through to the next candidate.
    }
  }

  toolMissAt = Date.now()
  return null
}

async function probe(file, ffprobe) {
  const { stdout } = await run(
    ffprobe,
    [
      '-v', 'error',
      '-print_format', 'json',
      '-show_streams',
      '-show_format',
      file,
    ],
    { timeout: PROBE_TIMEOUT_MS, maxBuffer: 4 * 1024 * 1024 },
  )
  return JSON.parse(stdout)
}

function videoDuration(info) {
  const formatDuration = Number(info?.format?.duration)
  if (Number.isFinite(formatDuration) && formatDuration > 0) return formatDuration

  const stream = (info?.streams || []).find((s) => s.codec_type === 'video')
  return Number(stream?.duration) || 0
}

/**
 * Render a JPEG preview for `srcPath`, or null when the format rules say one
 * would not pay off.
 *
 * Applies the same skip rules as the CLI: nothing for images that already fit
 * in 512 px, nothing under 128 bytes, and nothing that would cost more than a
 * quarter of the payload. A missing ffmpeg is *not* one of those rules — it
 * throws, because a media entry without a preview can never be repaired short
 * of re-encrypting the file.
 */
export async function makeThumbnail(srcPath) {
  const ext = path.extname(srcPath).slice(1).toLowerCase()
  const isVideo = VIDEO_EXT.has(ext)
  // Formats that can never carry a preview are the only silent skip.
  if (!isVideo && !IMAGE_EXT.has(ext)) return null

  const tools = await resolveTools()
  if (!tools) throw new Error('缺少内置 ffmpeg，无法生成预览图')

  let stat
  try {
    stat = await fsp.stat(srcPath)
  } catch {
    return null
  }
  if (stat.size <= 0) return null

  let info
  try {
    info = await probe(srcPath, tools.ffprobe)
  } catch {
    return null
  }

  const videoStream = (info.streams || []).find((s) => s.codec_type === 'video')
  if (!videoStream) return null

  // A still image that already fits gains nothing from a preview.
  if (!isVideo) {
    const width = Number(videoStream.width) || 0
    const height = Number(videoStream.height) || 0
    if (Math.max(width, height) <= THUMB_MAX_SIDE) return null
  }

  const args = ['-hide_banner', '-loglevel', 'error', '-nostdin']

  if (isVideo) {
    // 30% in: past the intro, still representative — same rule as the CLI.
    const duration = videoDuration(info)
    const seek = duration > 0 ? duration * 0.3 : 0
    if (seek > 0) args.push('-ss', seek.toFixed(3))
  }

  args.push(
    '-i', srcPath,
    '-frames:v', '1',
    '-vf', `scale=${THUMB_MAX_SIDE}:${THUMB_MAX_SIDE}:force_original_aspect_ratio=decrease`,
    '-f', 'image2pipe',
    '-vcodec', 'mjpeg',
    '-q:v', '3',
    '-',
  )

  let data
  try {
    const result = await run(tools.ffmpeg, args, {
      timeout: VIDEO_TIMEOUT_MS,
      maxBuffer: 16 * 1024 * 1024,
      encoding: 'buffer',
    })
    data = result.stdout
  } catch (err) {
    // Fall back to the very first frame for files whose 30% seek fails
    // (no index, broken timestamps, very short clips).
    if (!isVideo) return null
    try {
      const result = await run(
        tools.ffmpeg,
        [
          '-hide_banner', '-loglevel', 'error', '-nostdin',
          '-i', srcPath,
          '-frames:v', '1',
          '-vf', `scale=${THUMB_MAX_SIDE}:${THUMB_MAX_SIDE}:force_original_aspect_ratio=decrease`,
          '-f', 'image2pipe', '-vcodec', 'mjpeg', '-q:v', '3',
          '-',
        ],
        { timeout: VIDEO_TIMEOUT_MS, maxBuffer: 16 * 1024 * 1024, encoding: 'buffer' },
      )
      data = result.stdout
    } catch {
      return null
    }
  }

  if (!data || data.length < 128) return null
  // Never spend more than a quarter of the payload on its own preview.
  if (data.length * 4 >= stat.size) return null

  return data
}
