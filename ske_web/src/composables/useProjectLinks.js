/**
 * useProjectLinks — where users go to get the client builds.
 *
 * Only the CLI and the Android client are published there; the browser helper
 * (`ske_extension/`) is loaded unpacked from the repository, so there is no
 * install link to offer for it.
 *
 * `public/sw-decrypt.js` cannot import this module (it is registered as the
 * service worker, not bundled), so the same URL is repeated there — keep the
 * two in sync.
 */
export const RELEASES_URL = 'https://github.com/elysiawen/SakuraEncryptor/releases'
