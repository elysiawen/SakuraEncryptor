/**
 * File operations the cloud and local libraries both offer.
 *
 * These live outside `local` / `browse` on purpose: the two libraries show the
 * same verbs, and a verb defined twice is a verb that will drift apart the first
 * time one of them is reworded.
 */
export default {
  selectMode: 'Select',
  refresh: 'Refresh',
  selectAll: 'Select all',
  clearSelection: 'Clear selection',
  selectedCount: '{count} selected',

  download: 'Download',
  downloadCount: 'Download {count} files',
  deleteCount: 'Delete {count} items',

  emptyDirTitle: 'This folder is empty',

  mkdirTitle: 'New folder',
  folderNamePlaceholder: 'Folder name',
  create: 'Create',
  encryptName: 'Encrypt the name',
  rename: 'Rename',
  renameFolder: 'Rename folder',
  renameDesc: 'Encrypted entries are re-encrypted under the new name; plain ones stay plain.',
  newNamePlaceholder: 'New name',
  deleteTitle: 'Delete',

  copyLink: 'Copy playback link',
  externalPlayer: 'External player',
  linkCopied: 'Playback link copied; paste it into mpv / VLC. It expires when you copy another file or quit the app.',
  openedWith: 'Opened with {player}; keep the app running while it plays',

  queuedOne: 'Queued for download: {name}',
  queuedMany: 'Queued {count} files for download',
  created: 'Folder created: {name}',
  renamed: 'Renamed to: {name}',
  removedOne: 'Deleted: {name}',
  removedMany: 'Deleted {count} items',
  removeFailed: '{count} items could not be deleted: {error}',
}
