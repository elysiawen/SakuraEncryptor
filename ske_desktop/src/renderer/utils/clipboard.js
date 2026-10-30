/**
 * Copy-to-clipboard for the player windows.
 *
 * Writing goes through the main process instead of `navigator.clipboard`: the
 * renderer is sandboxed, and on Linux granting a selection also costs a window
 * manager round trip, while Electron's own clipboard module always works.
 */
export async function copyText(text) {
  const value = String(text || '')
  if (!value) return false

  try {
    const res = await window.ske?.clipboard?.write?.(value)
    return !!res?.ok
  } catch {
    return false
  }
}
