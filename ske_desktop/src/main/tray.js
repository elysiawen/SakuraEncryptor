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
 */
export function createTray({ iconPath, onShow, onNewWindow, onQuit }) {
  const image =
    iconPath && fs.existsSync(iconPath)
      ? nativeImage.createFromPath(iconPath)
      : nativeImage.createEmpty()

  const tray = new Tray(image)
  tray.setToolTip('Sakura Encryptor')

  const menu = Menu.buildFromTemplate([
    { label: '显示主窗口', click: onShow },
    { label: '新建窗口', click: onNewWindow },
    { type: 'separator' },
    { label: '退出', click: onQuit },
  ])

  tray.setContextMenu(menu)
  tray.on('click', onShow)
  // Windows fires double-click instead of click when the shell has one configured.
  tray.on('double-click', onShow)

  return tray
}
