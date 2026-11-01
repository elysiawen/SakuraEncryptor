/**
 * Mirrors `zh-CN/index.js` key for key; `npm run verify:i18n` fails when the two
 * disagree, so a namespace added on one side cannot be forgotten on the other.
 */
import browse from './browse.js'
import common from './common.js'
import diagnostics from './diagnostics.js'
import dialogs from './dialogs.js'
import downloads from './downloads.js'
import embedded from './embedded.js'
import encrypt from './encrypt.js'
import errors from './errors.js'
import files from './files.js'
import local from './local.js'
import lock from './lock.js'
import nav from './nav.js'
import player from './player.js'
import profiles from './profiles.js'
import settings from './settings.js'
import sidebar from './sidebar.js'
import titleBar from './titleBar.js'
import transfers from './transfers.js'
import tray from './tray.js'

export default {
  browse,
  common,
  diagnostics,
  dialogs,
  downloads,
  embedded,
  encrypt,
  errors,
  files,
  local,
  lock,
  nav,
  player,
  profiles,
  settings,
  sidebar,
  titleBar,
  transfers,
  tray,
}
