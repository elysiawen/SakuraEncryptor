import { reactive } from 'vue';
import { useLocalFiles } from './useLocalFiles.js';

/**
 * Remembers the picked folder across page reloads.
 *
 * The Service Worker holds `File` objects in memory, and a reload throws those
 * away — that is why a local folder used to come back empty. `File` objects
 * cannot be persisted, but a `FileSystemDirectoryHandle` can: it survives in
 * IndexedDB, and the files are re-read from it on demand.
 *
 * Browsers still require a user gesture to re-grant access after a reload, so
 * the flow is: silently reconnect when permission is still `granted`, otherwise
 * ask with one click (`requestPermission`). Firefox and Safari do not implement
 * `showDirectoryPicker`; there we fall back to `<input webkitdirectory>`, which
 * carries no handle and therefore cannot be remembered at all.
 */

const DB_NAME = 'ske-local';
const DB_VERSION = 1;
const STORE = 'handles';
const ROOT_KEY = 'root';

/** Reason codes returned by the restore helpers. */
export const LOCAL_STATUS = {
    RESTORED: 'restored',
    NEEDS_PERMISSION: 'prompt',
    NONE: 'none',
    UNSUPPORTED: 'unsupported',
    DENIED: 'denied',
    ERROR: 'error',
};

export const supportsDirectoryHandles =
    typeof window !== 'undefined' && 'showDirectoryPicker' in window;

const state = reactive({
    /** True while the folder tree is being re-scanned. */
    restoring: false,
    /** Name of the remembered folder, '' when there is none. */
    folderName: '',
    /** Last LOCAL_STATUS, so the view can explain what happened. */
    status: '',
    /** Human readable detail for LOCAL_STATUS.ERROR. */
    message: '',
});

function openDb() {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, DB_VERSION);
        request.onupgradeneeded = () => {
            const db = request.result;
            if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
        };
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error || new Error('IndexedDB unavailable'));
    });
}

async function withStore(mode, fn) {
    const db = await openDb();
    try {
        return await new Promise((resolve, reject) => {
            const tx = db.transaction(STORE, mode);
            const request = fn(tx.objectStore(STORE));
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
    } finally {
        db.close();
    }
}

const saveHandle = (handle) => withStore('readwrite', (store) => store.put(handle, ROOT_KEY));
const loadHandle = () => withStore('readonly', (store) => store.get(ROOT_KEY));
const dropHandle = () => withStore('readwrite', (store) => store.delete(ROOT_KEY));

/**
 * Walk [dir] and return `{ path, file }` entries.
 *
 * Paths are prefixed with the folder name, exactly like `webkitRelativePath`,
 * so a folder picked either way produces the same routes.
 */
async function collectEntries(dir, prefix) {
    const entries = [];
    for await (const handle of dir.values()) {
        const path = prefix + handle.name;
        if (handle.kind === 'directory') {
            entries.push(...(await collectEntries(handle, `${path}/`)));
        } else {
            entries.push({ path, file: await handle.getFile() });
        }
    }
    return entries;
}

async function registerHandle(handle) {
    const { registerEntries, setFolderName } = useLocalFiles();
    state.restoring = true;
    try {
        const entries = await collectEntries(handle, `${handle.name}/`);
        await registerEntries(entries);
        setFolderName(handle.name);
        state.folderName = handle.name;
    } finally {
        state.restoring = false;
    }
}

/**
 * Ask the browser for a folder and remember it.
 *
 * Throws `AbortError` when the user closes the picker.
 */
export async function pickLocalDirectory() {
    const handle = await window.showDirectoryPicker({ id: 'ske-local-root', mode: 'read' });
    await saveHandle(handle);
    await registerHandle(handle);
    state.status = LOCAL_STATUS.RESTORED;
    state.message = '';
    return handle;
}

/**
 * Try to bring the remembered folder back without asking the user anything.
 *
 * Returns `LOCAL_STATUS.NEEDS_PERMISSION` when the browser requires a gesture;
 * call [reconnectLocalDirectory] from a click handler in that case.
 */
export async function restoreLocalDirectory() {
    const { state: files, clearFiles, setFolderName } = useLocalFiles();

    // Already connected in this page session (e.g. navigating back into
    // /local): re-scanning a large tree would only cost time.
    if (files.isReady) {
        state.status = LOCAL_STATUS.RESTORED;
        return state.status;
    }

    if (!supportsDirectoryHandles) {
        state.status = LOCAL_STATUS.UNSUPPORTED;
        return state.status;
    }

    state.restoring = true;
    try {
        const handle = await loadHandle();
        if (!handle) {
            state.status = LOCAL_STATUS.NONE;
            return state.status;
        }

        state.folderName = handle.name;
        const permission = await handle.queryPermission({ mode: 'read' });
        if (permission === 'granted') {
            await registerHandle(handle);
            state.status = LOCAL_STATUS.RESTORED;
            return state.status;
        }
        if (permission === 'denied') {
            await dropHandle();
            clearFiles();
            setFolderName('');
            state.folderName = '';
            state.status = LOCAL_STATUS.DENIED;
            return state.status;
        }

        // 'prompt': the browser wants a click before handing the folder back.
        state.status = LOCAL_STATUS.NEEDS_PERMISSION;
        return state.status;
    } catch (err) {
        state.status = LOCAL_STATUS.ERROR;
        state.message = err?.message || '无法恢复本地目录';
        return state.status;
    } finally {
        state.restoring = false;
    }
}

/**
 * Re-grant access to the remembered folder. Must run inside a user gesture.
 */
export async function reconnectLocalDirectory() {
    const handle = await loadHandle();
    if (!handle) {
        state.status = LOCAL_STATUS.NONE;
        return state.status;
    }

    const permission = await handle.requestPermission({ mode: 'read' });
    if (permission !== 'granted') {
        state.status = LOCAL_STATUS.NEEDS_PERMISSION;
        return state.status;
    }

    try {
        await registerHandle(handle);
        state.status = LOCAL_STATUS.RESTORED;
        state.message = '';
    } catch (err) {
        state.status = LOCAL_STATUS.ERROR;
        state.message = err?.message || '无法读取该文件夹';
    }
    return state.status;
}

/** Forget the remembered folder (the browser keeps its own grant). */
export async function forgetLocalDirectory() {
    await dropHandle().catch(() => {});
    state.folderName = '';
    state.status = LOCAL_STATUS.NONE;
}

export function useLocalDirectory() {
    return {
        state,
        pickLocalDirectory,
        restoreLocalDirectory,
        reconnectLocalDirectory,
        forgetLocalDirectory,
    };
}
