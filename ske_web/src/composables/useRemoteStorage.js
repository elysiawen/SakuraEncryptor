/**
 * useRemoteStorage — the active remote provider.
 *
 * AList and OpenList live in their own modules; this file only remembers which
 * one the user signed in to and forwards each call. It contains no API logic,
 * so adding another compatible backend means adding one module and one entry
 * in `PROVIDERS`.
 */
import * as alist from './useAList.js'
import * as openList from './useOpenList.js'

const PROVIDERS = { alist, openlist: openList }
const PROVIDER_KEY = 'ske_remote_provider'
const SERVER_KEYS = {
    alist: 'ske_alist_server',
    openlist: 'ske_openlist_server',
}

export const PROVIDER_LABELS = {
    alist: 'AList',
    openlist: 'OpenList',
}

/** Stored provider id, falling back to AList for older sessions. */
export function getProvider() {
    const stored = sessionStorage.getItem(PROVIDER_KEY)
    return PROVIDERS[stored] ? stored : 'alist'
}

export function setProvider(provider) {
    if (!PROVIDERS[provider]) return
    sessionStorage.setItem(PROVIDER_KEY, provider)
}

/** Module of the active provider. */
export function getRemoteApi() {
    return PROVIDERS[getProvider()]
}

/** Sign in to [provider], which then becomes the active one. */
export async function login(provider, ...args) {
    setProvider(provider)
    return getRemoteApi().login(...args)
}

/** Last server address typed for [provider] (localStorage, per browser). */
export function savedServer(provider) {
    const key = SERVER_KEYS[provider]
    return key ? localStorage.getItem(key) || '' : ''
}

export function rememberServer(provider, server) {
    const key = SERVER_KEYS[provider]
    if (key && server) localStorage.setItem(key, server)
}

export function listDir(...args) {
    return getRemoteApi().listDir(...args)
}

export function renameItem(...args) {
    return getRemoteApi().renameItem(...args)
}

export function makeDir(...args) {
    return getRemoteApi().makeDir(...args)
}

export function uploadFile(...args) {
    return getRemoteApi().uploadFile(...args)
}

export function deleteItems(...args) {
    return getRemoteApi().deleteItems(...args)
}

export function getFileInfo(...args) {
    return getRemoteApi().getFileInfo(...args)
}

export function getServer() {
    return getRemoteApi().getServer()
}

export function logout() {
    return getRemoteApi().logout()
}
