/**
 * Handing one library entry to the outside world: a copied link, or an external
 * player.
 *
 * Neither is a path. Both are a handle to a decrypt session open in the main
 * process, which is what lets mpv/VLC seek through a `.ske` over plain HTTP
 * Range while the plaintext never reaches the disk. That shapes this module:
 *
 * - the session has to stay open after the command, so it is remembered here;
 * - each command keeps at most one such session, closing the previous one, or a
 *   user browsing a folder of videos would hold a dozen streams open;
 * - the player's own session is never touched: closing it would cut whatever is
 *   on screen.
 */
import { copyText } from '../utils/clipboard.js'
import { player, sameEntry } from './player.js'

/** Session kept alive because a link is on the clipboard. */
let linkId = ''
/** Session kept alive because an external player is reading it. */
let externalId = ''

/** Kinds the media router can turn into a stream. */
const PLAYABLE_KINDS = new Set(['video', 'audio', 'image'])

/** Whether a listing entry is something an external player could open. */
export function copyable(entry) {
  return !entry.isDir && PLAYABLE_KINDS.has(entry.kind)
}

function openSession(item) {
  if (item.mode === 'local') {
    return window.ske.local.play({ path: item.path, name: item.name })
  }
  return window.ske.media.openCloud({
    sessionId: item.sessionId,
    path: item.path,
    name: item.name,
  })
}

/**
 * A URL for [item], reusing the player's when that is already what is playing.
 *
 * Opening a second stream for the same file would only double the decrypting,
 * and the link would not match what the user is watching.
 */
async function sessionFor(item) {
  if (player.url && sameEntry(item, player.item)) {
    return { id: player.id, url: player.url, reused: true }
  }

  const res = await openSession(item)
  if (!res?.ok) throw new Error(res?.error || '无法打开文件')
  return { id: res.id, url: res.url, reused: false }
}

/** Close a session this module opened; never the player's, never a newer one. */
async function retire(previous, current) {
  if (previous && previous !== current && previous !== player.id) {
    // Best effort: a session that is already gone costs nothing to close.
    await window.ske.media.close(previous).catch(() => {})
  }
}

/**
 * Copy the stream URL of [item] (`{ mode, sessionId, path, name }`).
 *
 * Throws with a user-facing message: the callers are menu actions, and a silent
 * failure would look like the menu did nothing.
 */
export async function copyEntryLink(item) {
  const target = await sessionFor(item)

  if (!(await copyText(target.url))) {
    await retire(target.id, '')
    throw new Error('复制失败，请重试')
  }

  if (!target.reused) {
    await retire(linkId, target.id)
    linkId = target.id
  }
  return target
}

/**
 * Players the submenu can offer.
 *
 * Not cached here: the main process caches detection already, and the settings
 * page can change the preferred player at any time.
 */
export async function listExternalPlayers() {
  const res = await window.ske.player.listExternal()
  return res?.ok ? res.players || [] : []
}

/**
 * Submenu entries for the "外部播放器" command.
 *
 * `run(player)` is the click handler; building the list launches nothing, so a
 * right-click stays cheap even before the first player has been detected.
 */
export function playerMenuItems(players, run) {
  if (!players.length) {
    return [{ label: '未找到播放器，请在设置里指定', icon: '⚠', disabled: true }]
  }

  return players.map((player) => ({
    label: player.preferred ? `${player.name}（默认）` : player.name,
    icon: '▶',
    action: () => run(player),
  }))
}

/** Launch [exe] — or the configured player — on [item]. */
export async function openEntryExternally(item, exe = '') {
  const target = await sessionFor(item)
  const res = await window.ske.player.openExternal(target.url, exe)

  if (!res?.ok) {
    // The stream was opened for a player that never started: release it rather
    // than leaving a session nobody reads.
    await retire(target.id, '')
    throw new Error(res?.error || '无法唤起外部播放器')
  }

  if (!target.reused) {
    await retire(externalId, target.id)
    externalId = target.id
  }
  return res
}
