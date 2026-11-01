/**
 * The cloud library. Verbs it shares with the local library live in `files`;
 * what is here is specific to talking to an AList / OpenList server.
 */
export default {
  otpTitle: 'Two-factor authentication',
  otpDesc: 'This account has two-factor authentication enabled; enter the code from your authenticator app.',
  otpPlaceholder: 'Authentication code',
  otpConfirm: 'Verify and connect',
  otpInvalid: 'That code is not valid; try again',

  noProfileTitle: 'No cloud profile selected',
  noProfileDesc: 'The cloud library reads encrypted folders through AList / OpenList. Add an account to browse and play inside the app; the password never leaves this machine.',
  noProfileAction: 'Go to profiles',

  starting: 'Starting the built-in server…',
  connecting: 'Connecting to {name}…',
  connectFailed: 'Cannot reach the cloud',
  enterOtp: 'Enter the code',

  uploadChipTitle: 'Upload progress',
  uploading: 'Uploading {count} · {percent}%',
  itemCount: '{count} items',
  upload: 'Upload',
  gridView: 'Grid view',
  listView: 'List view',
  grid: 'Grid',
  list: 'List',

  emptyDirDesc: 'Encrypted and plain files both show up here. Encrypted entries are marked, and their names and contents only appear once the right media password is entered.',
  encryptedBadge: 'Encrypted file',
  mkdirDesc: 'The name is stored as an encrypted token in the cloud tree; untick to use a plain name.',

  dropTitle: 'Release to upload to “{path}”',
  dropHint: 'Encrypts and uploads with your settings once confirmed',
  dragNoFiles: 'Nothing droppable in what you dragged in (folders are not expanded)',
  ignoredFolders: 'Ignored {count} folders',
  pickFailed: 'Could not open the file picker',
  uploadQueued: 'Queued {count} files for upload',
  uploadFailed: '{count} files failed to upload: {error}',
  uploadDone: 'Upload complete',

  /** The confirmation dialog shown before files leave the machine. */
  uploadTitle: 'Upload files',
  uploadTo: 'Will upload to “{folder}”',
  uploadEncrypt: 'Encrypt on the way (.ske containers)',
  uploadPlainHint: 'With this off the original files are uploaded, so the cloud folder holds plaintext.',
  uploadEncryptHint: 'Files are encrypted into containers on this machine first, so the cloud only ever sees ciphertext.',
  uploadAdding: 'Adding…',
  uploadCount: 'Upload {count} files',

  nothingDownloadable: 'None of the selected items can be downloaded',
  removeOne: '“{name}” will be deleted from the cloud. This cannot be undone.',
  removeMany: 'The {count} selected items will be deleted from the cloud. This cannot be undone.',

  tagLocked: 'Cannot decrypt',
  tagEncrypted: 'Encrypted',
  tagPlain: 'Plain',

  /** Clicking a file the viewer cannot open. */
  playLocked: 'This file cannot be decrypted; check the media password',
  playUnsupported: 'This kind of file cannot be played',
}
