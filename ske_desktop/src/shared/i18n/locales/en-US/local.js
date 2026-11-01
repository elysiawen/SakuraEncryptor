/**
 * The local library: a folder on disk, unlocked with its own password.
 *
 * The verbs it shares with the cloud library live in `files`.
 */
export default {
  unlockTitle: 'Unlock the local library',
  unlockDesc: 'Local files use their own password, unrelated to cloud profiles. It is kept in memory only, so you enter it again after a restart.',
  passwordPlaceholder: 'Local encryption password',
  verifying: 'Verifying…',
  unlock: 'Unlock',

  noRoot: 'No folder chosen',
  pickDir: 'Choose folder',
  lock: 'Lock',

  emptyRootTitle: 'Choose a local folder',
  emptyRootDesc: 'Reads encrypted and plain files straight off the disk; no upload, no browser permission.',
  emptyRootAction: 'Choose folder',
  readFailed: 'Could not read the folder',
  emptyDirDesc: 'Encrypt files into it with the workbench and they show up here.',

  mkdirDesc: 'The name is written as an encrypted token by default; untick to use a plain name.',

  removeOne: '“{name}” will be deleted from disk. This cannot be undone.',
  removeMany: 'The {count} selected items will be deleted from disk. This cannot be undone.',

  notFound: 'Could not find {file} in this folder',
  unlocked: 'Local library unlocked',
  needUnlock: 'Unlock the local library first',
  lockedToast: 'Local library locked',
  decryptFailed: 'This file cannot be decrypted; check the password',
  unsupported: 'This file type cannot be played yet',

  nothingDownloadable: 'None of the selected items is a downloadable encrypted file',
}
