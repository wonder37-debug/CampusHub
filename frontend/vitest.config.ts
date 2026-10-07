import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 与 vite.config.ts 保持一致的 alias，确保 @ 指向 src
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'happy-dom',
    globals: true,
    include: ['tests/**/*.{test,spec}.ts', 'src/test/**/*.{test,spec}.ts'],
    setupFiles: ['./src/test/setup.ts'],
    coverage: {
      provider: 'v8'
    }
  }
})
