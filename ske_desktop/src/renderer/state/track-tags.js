/**
 * Track metadata cache, keyed by stream URL.
 *
 * The sidebar bar and the music window both ask for the same track's artwork,
 * title, artist and album, and extracting them means walking the container's
 * tags over ranged reads. One map means one extraction per track for the whole
 * session, and all four fields come out of that single walk.
 *
 * Everything reads through a filename fallback: a file with no tags still has
 * to be labelled with something, and the name the user picked is the only
 * truthful answer left.
 */
import { reactive } from 'vue'
import { readAudioTags } from '../utils/audio-tags.js'

const NOTHING = { cover: '', title: '', artist: '', album: '' }

const entries = reactive(new Map())
/** In-flight read per url: two watchers on one track must not both walk it. */
const pending = new Map()
/** Last attempt per url: a retry must not become a hot loop. */
const attempts = new Map()
const RETRY_AFTER_MS = 3000

export function tagsFor(url) {
  return (url && entries.get(url)) || NOTHING
}

export async function ensureTags(url) {
  if (!url || entries.has(url)) return
  if (pending.has(url)) return pending.get(url)

  const last = attempts.get(url) || 0
  if (Date.now() - last < RETRY_AFTER_MS) return
  attempts.set(url, Date.now())

  const task = readAudioTags(url)
    .then((tags) => {
      // Only real results are cached: a tagless file costs nothing to check
      // again, while a read that failed early (stream not ready, expired url)
      // must not blank the labels for the rest of the session.
      if (tags.cover || tags.title || tags.artist || tags.album) entries.set(url, tags)
    })
    .catch(() => {
      /* the filename fallback stays */
    })
    .finally(() => pending.delete(url))

  pending.set(url, task)
  return task
}

/**
 * The file name without its extension.
 *
 * `晴天.mp3` is the user's title for a file that carries none; the extension
 * is transport, not part of the name, and the player shows the name it read.
 */
export function trackName(name) {
  const full = String(name || '').trim()
  const dot = full.lastIndexOf('.')
  return dot > 0 ? full.slice(0, dot) : full
}

/**
 * What the interface shows for one track: the tag values when the file has
 * them, the file name wherever it does not.
 */
export function describeTrack(url, name) {
  const tags = tagsFor(url)
  return {
    cover: tags.cover,
    title: tags.title || trackName(name),
    artist: tags.artist,
    album: tags.album,
    /** `artist · album`, skipping whichever half the file does not carry. */
    byline: [tags.artist, tags.album].filter(Boolean).join(' · '),
  }
}
