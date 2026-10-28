/**
 * Preview generation, matching the rules in docs/SKE-FORMAT.md §9 so a file
 * encrypted here looks the same as one encrypted by the CLI or the Android app.
 *
 * Decoding is delegated to a system ffmpeg. That keeps the app free of native
 * image dependencies, and ffmpeg is the one tool that can open every container
 * the media library might contain. When it is missing, encryption still works —
 * the container simply carries no preview.
 */
import { execFile } from 'node:child_process'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { promisify } from 'node:util'

const run = promisify(execFile)

const THUMB_MAX_SIDE = 512
const VIDEO_TIMEOUT_MS = 30_000
const PROBE_TIMEOUT_MS = 15_000

const VIDEO_EXT = new Set([
  'mp4', 'm4v', 'mkv', 'mov', 'avi', 'webm', 'ts', 'm2ts', 'flv', 'wmv', 'mpg', 'mpeg', '3gp',
])
const IMAGE_EXT = new Set(['jpg', 'jpeg', 'png', 'webp', 'bmp', 'avif', 'heic', 'heif', 'gif'])

let toolCache = null

/** Locate ffmpeg/ffprobe once; null when the machine has neither. */
export async function resolveTools() {
  if (toolCache) return toolCache

  try {
    await run('ffmpeg', ['-version'], { timeout: 8000 })
    await run('ffprobe', ['-version'], { timeout: 8000 })
    toolCache = { ffmpeg: 'ffmpeg', ffprobe: 'ffprobe' }
  } catch {
    toolCache = null
  }
  return toolCache
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
 * Render a JPEG preview for `srcPath`, or null when none can be produced.
 *
 * Applies the same skip rules as the CLI: nothing for images that already fit
 * in 512 px, nothing under 128 bytes, and nothing that would cost more than a
 * quarter of the payload.
 */
export async function makeThumbnail(srcPath) {
  const tools = await resolveTools()
  if (!tools) return null

  const ext = path.extname(srcPath).slice(1).toLowerCase()
  const isVideo = VIDEO_EXT.has(ext)
  if (!isVideo && !IMAGE_EXT.has(ext)) return null

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
