import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const entry = (file) => fileURLToPath(new URL(file, import.meta.url))

// Two entries, on purpose:
//   index.html — the shell (sidebar, library, mini player)
//   stage.html — the player window for video and images
// The player window must not boot the shell, so it cannot be a route of the SPA.
export default defineConfig({
  root: 'src/renderer',
  base: './',
  plugins: [vue()],
  build: {
    outDir: '../../out/renderer',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        shell: entry('./src/renderer/index.html'),
        stage: entry('./src/renderer/stage.html'),
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
  },
})
