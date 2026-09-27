import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // In development Vite serves the SPA and forwards API calls to the backend,
    // so the frontend code can use same-origin relative URLs in every
    // environment. In Docker nginx plays the same role.
    proxy: {
      '/api': {
        target: process.env.BACKEND_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    // Rulebook narration is fetched as binary; nothing here needs inlining.
    assetsInlineLimit: 4096,
  },
})
