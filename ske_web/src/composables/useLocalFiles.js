import { reactive } from 'vue';

const state = reactive({
    files: [], // Array of { name, path }
    folderName: '', // Display name of the picked folder, '' for loose files
    isReady: false
});

export function useLocalFiles() {
    /**
     * Hand the files to the Service Worker, which is the only place that can
     * read them once a request comes in through /ske-local/.
     *
     * `entries` are `{ path, file }`, where `path` is relative to the picked
     * folder and therefore matches the routes the browser renders.
     */
    async function registerEntries(entries) {
        if (!('serviceWorker' in navigator && navigator.serviceWorker.controller)) {
            console.error('[LocalFiles] Service Worker not active');
            throw new Error('Service Worker not active. Please refresh.');
        }

        navigator.serviceWorker.controller.postMessage({
            type: 'REGISTER_LOCAL_FILES',
            files: entries
        });
        console.log(`[LocalFiles] Registered ${entries.length} files to SW`);
        state.files = entries.map(e => ({ name: e.file.name, path: e.path }));
        state.isReady = true;
    }

    /**
     * Scan a directory or single files (the `<input webkitdirectory>` path) and
     * register them with the Service Worker.
     */
    async function registerFiles(fileList) {
        const entries = [];
        for (const file of fileList) {
            const path = file.webkitRelativePath || file.name;
            entries.push({ path, file });
        }
        await registerEntries(entries);
    }

    function clearFiles() {
        if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
            navigator.serviceWorker.controller.postMessage({ type: 'CLEAR_LOCAL_FILES' });
        }
        state.files = [];
        state.folderName = '';
        state.isReady = false;
    }

    /** Set by the directory-handle flow so the UI can name the connected folder. */
    function setFolderName(name) {
        state.folderName = name || '';
    }

    return {
        state,
        registerFiles,
        registerEntries,
        clearFiles,
        setFolderName
    };
}
