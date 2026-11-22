import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { VitePWA } from 'vite-plugin-pwa'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
  // Disable PWA for Storybook builds
  const isStorybook = process.env.STORYBOOK === 'true'

  return {
    plugins: [
      vue(),
      vuetify({ autoImport: true }),
      !isStorybook && VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'robots.txt'],
      manifest: {
        name: 'RAG in a Box Admin',
        short_name: 'RAG Admin',
        description: 'Admin interface for RAG in a Box',
        theme_color: '#1976D2',
        background_color: '#ffffff',
        display: 'standalone',
        scope: '/admin/',
        start_url: '/admin/',
        icons: [
          {
            src: 'favicon.svg',
            sizes: 'any',
            type: 'image/svg+xml',
            purpose: 'any'
          },
          {
            src: 'favicon.svg',
            sizes: 'any',
            type: 'image/svg+xml',
            purpose: 'maskable'
          }
        ]
      },
      workbox: {
        // Precache the app shell only. API responses are deliberately NOT
        // cached: they are authenticated, per-user and must not be served
        // stale (or leak between sessions) from the service worker.
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2}']
      }
    }),
  ].filter(Boolean),
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  base: '/admin/',
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/health': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // Live updates (stores/websocket.ts)
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rolldownOptions: {
      output: {
        // Split vendor chunks (Vite 8 / Rolldown: codeSplitting.groups replaces manualChunks)
        codeSplitting: {
          groups: [
            { name: 'vendor-vue', test: /[\\/]node_modules[\\/](@vue|vue|vue-router|pinia)[\\/]/ },
            { name: 'vendor-vuetify', test: /[\\/]node_modules[\\/]vuetify[\\/]/ },
            { name: 'vendor-axios', test: /[\\/]node_modules[\\/]axios[\\/]/ },
            { name: 'vendor-chartjs', test: /[\\/]node_modules[\\/](chart\.js|vue-chartjs|@kurkle)[\\/]/ },
            { name: 'vendor-swagger', test: /[\\/]node_modules[\\/]swagger-ui-dist[\\/]/ }
          ]
        }
      }
    }
  }
}})
