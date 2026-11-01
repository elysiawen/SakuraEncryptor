/**
 * System tray.
 *
 * The tray is what makes "close to tray" honest: an app that hides its window
 * with no way back is a bug, so the tray and that setting ship together.
 *
 * The icon is optional. In development the repository root holds the artwork,
 * but a packaged build may not, and Electron renders an empty image as an
 * invisible-but-working tray entry rather than crashing.
 */
import { Menu, Tray, nativeImage } from 'electron'
import fs from 'node:fs'

/**
 * @param {object} options
 * @param {string} options.iconPath
 * @param {() => void} options.onShow
 * @param {() => void} options.onNewWindow
 * @param {() => void} options.onQuit
 * @param {(key: string) => string} options.t  main-process translator
 */
export function createTray({ iconPath, onShow, onNewWindow, onQuit, t }) {
  const image =
    iconPath && fs.existsSync(iconPath)
      ? nativeImage.createFromPath(iconPath)
      : nativeImage.createEmpty()

  const tray = new Tray(image)

  /**
   * Labels are baked into the native menu when it is built, so a language
   * switch has to build a whole new menu — mutating the old one is not a thing
   * Electron offers. `refresh` is attached to the tray because that is what the
   * settings channel needs a handle on.
   */
  tray.refresh = () => {
    tray.setToolTip(t('tray.tooltip'))
    tray.setContextMenu(
      Menu.buildFromTemplate([
        { label: t('tray.showWindow'), click: onShow },
        { label: t('tray.newWindow'), click: onNewWindow },
        { type: 'separator' },
        { label: t('tray.quit'), click: onQuit },
      ]),
    )
  }

  tray.refresh()
  tray.on('click', onShow)
  // Windows fires double-click instead of click when the shell has one configured.
  tray.on('double-click', onShow)

  return tray
}
