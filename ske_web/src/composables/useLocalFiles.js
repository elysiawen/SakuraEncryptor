import { ref, reactive } from 'vue'

const state = reactive({
    files: [], // Array of { name, path, file }
    isReady: false
})

export function useLocalFiles() {
    /**
     * Scan a directory or single files and register them with the Service Worker.
     */
    async function registerFiles(fileList) {
        const entries = []
        for (const file of fileList) {
            const path = file.webkitRelativePath || file.name
            entries.push({ path, file })
        }

        // Notify Service Worker
        if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
            navigator.serviceWorker.controller.postMessage({
                type: 'REGISTER_LOCAL_FILES',
                files: entries
            })
            console.log(`[LocalFiles] Registered ${entries.length} files to SW`)
            state.files = entries.map(e => ({ name: e.file.name, path: e.path }))
            state.isReady = true
        } else {
            console.error('[LocalFiles] Service Worker not active')
            throw new Error('Service Worker not active. Please refresh.')
        }
    }

    function clearFiles() {
        if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
            navigator.serviceWorker.controller.postMessage({ type: 'CLEAR_LOCAL_FILES' })
        }
        state.files = []
        state.isReady = false
    }

    return {
        state,
        registerFiles,
        clearFiles
    }
}
