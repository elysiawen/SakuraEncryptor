/**
 * useDecryptProxy — Single source of truth for Service-Worker decrypt-proxy URLs.
 *
 * The Service Worker intercepts `/ske-decrypt/` and streams back decrypted
 * plaintext. The query contract is:
 *
 *   url = the upstream (encrypted) file link              [required]
 *   size = the upstream file size in bytes                [optional]
 *          Lets the SW derive the plaintext length when the upstream host
 *          does not expose Content-Range.
 *
 * Append `&download=1` (see useFileDownload) to switch the proxy into
 * whole-file streaming mode instead of the 5 MiB-capped playback mode.
 */

export const DECRYPT_PROXY_PATH = '/ske-decrypt/'

/** Build a decrypt-proxy URL for an upstream (encrypted) file link. */
export function buildDecryptProxyUrl(rawUrl, size = 0) {
    const sizeParam = size ? `&size=${size}` : ''
    return `${DECRYPT_PROXY_PATH}?url=${encodeURIComponent(rawUrl)}${sizeParam}`
}
