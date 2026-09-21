/**
 * useFileDetection — File type detection utilities
 */

const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg']
const VIDEO_EXTS = ['mp4', 'mkv', 'avi', 'mov', 'wmv', 'flv', 'webm', 'ts', 'm4v']
const AUDIO_EXTS = ['mp3', 'wav', 'ogg', 'flac', 'aac', 'm4a']
const SUBTITLE_EXTS = ['srt', 'ass', 'ssa', 'vtt']

export function getExt(name) {
  if (!name) return ''
  return name.split('.').pop()?.toLowerCase() || ''
}

export function isImage(name) {
  return IMAGE_EXTS.includes(getExt(name))
}

export function isVideo(name) {
  return VIDEO_EXTS.includes(getExt(name))
}

export function isAudio(name) {
  return AUDIO_EXTS.includes(getExt(name))
}

export function isSubtitle(name) {
  return SUBTITLE_EXTS.includes(getExt(name))
}

export function getFileType(name) {
  const ext = getExt(name)
  if (IMAGE_EXTS.includes(ext)) return 'image'
  if (VIDEO_EXTS.includes(ext)) return 'video'
  if (AUDIO_EXTS.includes(ext)) return 'audio'
  if (SUBTITLE_EXTS.includes(ext)) return 'subtitle'
  return 'file'
}

export function getFileIcon(item) {
  if (item.is_dir || item.isDir) return '📁'
  const type = getFileType(item.decName || item.name)
  const icons = { image: '🖼️', video: '🎬', audio: '🎵', subtitle: '💬', file: '📄' }
  return icons[type] || '📄'
}

export function getFileTagType(item) {
  if (item.is_dir || item.isDir) return 'folder'
  return getFileType(item.decName || item.name)
}

export function formatSize(bytes) {
  if (!bytes) return ''
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return size.toFixed(i > 0 ? 1 : 0) + ' ' + units[i]
}

export function formatBytes(b) {
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB'
  if (b < 1024 * 1024 * 1024) return (b / (1024 * 1024)).toFixed(1) + ' MB'
  return (b / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}
