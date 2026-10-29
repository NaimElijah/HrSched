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
  build: {
    // The bundle is a copy of React, react-dom and scheduler sent to every browser, and their MIT
    // licence asks that its notice travel with every copy. This writes the notices of everything
    // bundled to dist/.vite/license.md. Serving it is the edge's job (#162); what else HrSched owes
    // for what it ships is #280.
    license: true,
  },
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
    // Every run reads local time in one zone west of Greenwich, whatever the machine's own zone is.
    // CI runs in UTC, where local time and UTC agree, so a test guarding the local-date hazard in
    // localDates.ts would pass there with the hazard reintroduced. A zone that keeps daylight saving
    // also lets an offset change be tested. Set here rather than as a TZ= prefix on the npm script,
    // which Windows' cmd does not understand; Node rereads TZ when it is assigned at runtime.
    env: { TZ: 'America/Los_Angeles' },
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
