/**
 * Audio cover cache, keyed by stream URL.
 *
 * Both the sidebar bar and the music page ask for the same track's artwork, and
 * extracting it means a ranged fetch of the file head. One map means one fetch
 * per track for the whole session.
 */
import { reactive } from 'vue'
import { readCover } from '../utils/audio-tags.js'

const covers = reactive(new Map())

export function coverFor(url) {
  return (url && covers.get(url)) || ''
}

export async function ensureCover(url) {
  if (!url || covers.has(url)) return

  // Empty string doubles as "checked, nothing found", so a tagless file is not
  // re-read every time the page mounts.
  covers.set(url, '')
  try {
    covers.set(url, await readCover(url))
  } catch {
    covers.set(url, '')
  }
}
