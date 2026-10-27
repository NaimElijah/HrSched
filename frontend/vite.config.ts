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
    // One process per test file, as Vitest 5 already defaults to, pinned because it is load-bearing:
    // the session's BroadcastChannel is module-level, and Node delivers it across worker threads, so
    // under 'threads' one file's endSession would end sessions in the files running beside it.
    pool: 'forks',
    /*
     * Reported, never enforced: no thresholds, and `enabled` defaults to false, so this block
     * only takes effect under --coverage and `npm run verify` is unchanged. See ADR-015.
     *
     * `include` is required. Without it only files a test already touches are listed, which is
     * the opposite of what a report meant to find gaps is for.
     */
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      reportsDirectory: './coverage',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/main.tsx', 'src/vite-env.d.ts', 'src/test-setup.ts', 'src/**/*.test.{ts,tsx}'],
    },
  },
});
