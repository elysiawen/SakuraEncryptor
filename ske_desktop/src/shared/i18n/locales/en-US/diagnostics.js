/**
 * The playback diagnostics page.
 *
 * The log lines are user-visible text, so they are translated too — but the
 * values inside them (durations, MIME types, bytes) stay as produced.
 */
export default {
  title: 'Playback diagnostics',
  subtitle: 'Runs a real playback and a seek against the chunk-decrypt endpoint, to see what this machine can decode.',
  back: 'Back',

  file: 'Encrypted file',
  pickFile: 'Choose file',
  targetPlaceholder: 'An absolute local path, or a remote https:// URL',
  password: 'Password',
  passwordPlaceholder: 'The password used when encrypting',
  displayName: 'Decrypted file name',
  displayNamePlaceholder: 'e.g. movie.mp4 — this decides the MIME type the player uses',
  open: 'Open and play',
  decrypting: 'Decrypting…',

  rangeTitle: 'Range / Seek diagnostics',
  runTest: 'Run test',
  testing: 'Testing…',
  logEmpty: 'Open a file, then run the diagnostics to check chunk decryption and arbitrary seeking.',

  needInput: 'Enter a file and a password first',
  openFailed: 'Failed to open: {error}',
  sessionOpened: 'Decryption session open: {size}, MIME {mime}',
  plainPassthrough: 'Not a .ske container; passing through as plaintext: {size}, MIME {mime}',
  timeout: '{event} timed out',
  unknown: 'unknown',

  noDuration: 'Duration unknown; skipping the seek test (this machine cannot decode the container or codec).',
  needExternal: 'Verdict: an external player backend is required.',
  seekOk: 'seek → {at}s succeeded in {ms}ms (landed at {actual}s)',
  seekFail: 'seek → {at}s failed or timed out',
  randomOk: '{count}/8 random seeks succeeded, {avg}ms average, {max}ms slowest',
  randomFail: 'Every random seek failed.',
  verdictOk: 'Verdict: the native video element plays this container; chunk decryption and seeking work.',
  verdictBad: 'Verdict: the native video element does not really support this container; it needs a remux or an external player backend.',
  interrupted: 'Diagnostics stopped: {error}',
}
