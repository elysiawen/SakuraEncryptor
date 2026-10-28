/**
 * Light / dark theme.
 *
 * The theme is a document-level attribute, not a component state: every token in
 * `styles.css` hangs off `:root`, so flipping `data-theme` repaints the whole app
 * — including the player window, which has no settings UI of its own.
 *
 * `localStorage` is the fast path: it paints the correct colour before the first
 * IPC round-trip, so there is no dark flash on a light-themed start. The
 * authoritative copy still lives in settings.json.
 */
import { patchSettings, session } from './session.js'

const CACHE_KEY = 'ske.theme'

export function currentTheme() {
  return document.documentElement.dataset.theme === 'light' ? 'light' : 'dark'
}

export function applyTheme(theme) {
  const value = theme === 'light' ? 'light' : 'dark'
  document.documentElement.dataset.theme = value
  try {
    localStorage.setItem(CACHE_KEY, value)
  } catch {
    // A disabled storage backend only costs one frame of flash on next start.
  }
  return value
}

/**
 * Paint from cache immediately, then trust the persisted setting.
 *
 * The player window has no bootstrap of its own, so this also fills
 * `session.settings` there — the volume and seek step read from it.
 */
export async function initTheme() {
  let cached = 'dark'
  try {
    cached = localStorage.getItem(CACHE_KEY) || 'dark'
  } catch {
    // Keep the default.
  }
  applyTheme(cached)

  const res = await window.ske?.settings?.get().catch(() => null)
  if (res?.ok) {
    session.settings = res.settings
    applyTheme(res.settings?.theme)
  }
}

/** Keep every other window (the player) in step with this one. */
export function listenThemeChanges() {
  window.ske?.theme?.onChange?.((theme) => applyTheme(theme))
}

/** Toggle, repaint instantly, persist, then tell the other windows. */
export async function toggleTheme() {
  const next = currentTheme() === 'light' ? 'dark' : 'light'
  applyTheme(next)

  try {
    await patchSettings({ theme: next })
    await window.ske?.theme?.broadcast?.(next)
  } catch {
    // The UI already switched; a failed write only means it reverts next launch.
  }
  return next
}
