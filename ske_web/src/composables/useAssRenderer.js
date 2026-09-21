/**
 * useAssRenderer — JASSUB (libass/WASM) wrapper for full-featured ASS rendering.
 *
 * JASSUB renders SSA/ASS subtitles into a canvas stacked on top of the video,
 * preserving fonts, positioning and effects that WebVTT cannot express.
 *
 * The worker / WASM / font assets are imported as URLs so Vite bundles them
 * correctly and we never rely on JASSUB's own relative-path resolution.
 */
import JASSUB from 'jassub'
import workerUrl from 'jassub/dist/worker/worker.js?worker&url'
import wasmUrl from 'jassub/dist/wasm/jassub-worker.wasm?url'
import modernWasmUrl from 'jassub/dist/wasm/jassub-worker-modern.wasm?url'
import defaultFontUrl from 'jassub/dist/default.woff2?url'

let instance = null
// Pristine (scale = 1) style snapshots, used as the baseline so repeated
// scaling never compounds on top of an already-scaled value.
let baseStyles = null

/**
 * Rescale every ASS style's FontSize by *scale*.
 *
 * libass has no "font scale factor" exposed here, so we rewrite each style's
 * absolute FontSize while keeping the relative differences between styles
 * (dialogue / signs / titles) intact.
 */
async function applyScale(scale) {
    if (!instance) return
    try {
        const styles = await instance.renderer.getStyles()
        if (!baseStyles || baseStyles.length !== styles.length) {
            baseStyles = styles.map(style => ({ ...style }))
        }
        for (let i = 0; i < baseStyles.length; i++) {
            await instance.renderer.setStyle(
                { ...baseStyles[i], FontSize: baseStyles[i].FontSize * scale },
                i,
            )
        }
        // Styles changed — force a repaint (needed while the video is paused).
        await instance.resize(true)
    } catch (err) {
        console.warn('[JASSUB] failed to apply scale', err)
    }
}

/** Tear down the current renderer, if any. */
export async function clearAssSubtitle() {
    const current = instance
    instance = null
    baseStyles = null
    if (!current) return
    try {
        await current.destroy()
    } catch (err) {
        console.warn('[JASSUB] destroy failed', err)
    }
}

/** Render *content* (raw ASS/SSA text) on top of *videoEl*. */
export async function renderAssSubtitle(videoEl, content, scale = 1) {
    await clearAssSubtitle()
    if (!videoEl || !content) return

    const renderer = new JASSUB({
        video: videoEl,
        subContent: content,
        workerUrl,
        wasmUrl,
        modernWasmUrl,
        availableFonts: { 'liberation sans': defaultFontUrl },
        defaultFont: 'liberation sans',
    })
    instance = renderer

    try {
        await renderer.ready
        await applyScale(scale)
    } catch (err) {
        console.error('[JASSUB] failed to initialise', err)
        if (instance === renderer) await clearAssSubtitle()
    }
}

/** Adjust the font size of the currently rendered ASS subtitle. */
export async function setAssScale(scale) {
    await applyScale(scale)
}
