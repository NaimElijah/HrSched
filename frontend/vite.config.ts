import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * The dev server proxies /api to the backend, so the browser only ever talks to one origin.
 *
 * That is why there is no CORS configuration anywhere in this repository: with a proxy there is no
 * cross-origin request to permit. Adding CORS to the backend would mean the backend knowing the
 * UI's deployment shape, which ADR-013 deliberately avoids.
 *
 * defineConfig comes from vitest/config, not vite: as of Vitest 4 the `test` key is no longer part
 * of Vite's own config type and using vite's defineConfig fails to typecheck.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    css: false,
  },
});
