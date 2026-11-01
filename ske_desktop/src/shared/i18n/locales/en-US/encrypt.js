/** The encrypt workbench: file queue and the text converter. */
export default {
  title: 'Encrypt',
  subtitleFiles: 'Files are encrypted locally, file names included; drop them in to start.',
  subtitleText: 'Turn text into a ciphertext you can copy, or turn one back into plaintext; it uses the same key as file names.',

  tabFiles: 'Files',
  tabText: 'Text',
  clearFinished: 'Clear finished',

  outputDir: 'Output folder',
  outputDirPlaceholder: 'Choose an output folder',
  choose: 'Choose',
  password: 'Encryption password',
  passwordPlaceholder: 'Leave empty to use the media password of the active profile',
  passwordHintText: 'Encryption and decryption share this one password; the ciphertext also opens on Android, the CLI and the web client.',

  dropTitle: 'Drop files or folders here',
  dropReady: 'Encrypted output goes to the folder above',
  dropIdle: 'Pick an output folder first, then drop files',
  pickFiles: 'Choose files',
  pickFolder: 'Choose folder',
  dropUnreadable: 'Could not read the dropped file paths',

  queue: 'Queue',
  queueCount: '{running} running · {total} total',
  queueEmpty: 'The queue is empty. Large files stream in 1 MiB chunks, so memory use does not grow with file size.',
  fileProgress: '{done}/{total} files',

  statusScanning: 'Scanning',
  statusQueued: 'Queued',
  statusRunning: 'Encrypting',
  statusDone: 'Done',
  statusCancelled: 'Cancelled',
  statusFailed: 'Failed',

  needOutputDir: 'Choose an output folder first',
  queued: 'Added {count} to the queue',

  content: 'Content',
  contentPlaceholder: 'Text to encrypt, or a ciphertext to paste',
  encrypt: 'Encrypt',
  decrypt: 'Decrypt',
  result: 'Result',
  chars: '{count} characters',
  copy: 'Copy',
  copied: 'Copied to the clipboard',
  missingInput: 'Enter something first',
  convertFailed: 'Conversion failed',
}
