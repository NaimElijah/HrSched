import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

const CALL_THROUGH_CLIENT = 'Call the backend through src/api/client.ts, the only module that may.';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'coverage'] },
  js.configs.recommended,
  tseslint.configs.recommended,
  // configs.flat.* is the flat-config shape; configs['recommended-latest'] is still legacy in 7.1.1
  reactHooks.configs.flat['recommended-latest'],
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2024,
      globals: { window: 'readonly', document: 'readonly', fetch: 'readonly', console: 'readonly' },
    },
    rules: {
      // The API boundary is the one place untyped data enters; everything inward is typed.
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/consistent-type-imports': 'error',
      'no-console': 'error',
    },
  },
  {
    // A request that bypasses client.ts goes out without the bearer token and is never renewed on a
    // 401. Tests get no exemption: they replace fetch by name through vi.stubGlobal, and one that
    // called it would reach the real network.
    // fetch is not the only way out: XMLHttpRequest, EventSource, WebSocket and navigator.sendBeacon
    // each send a request too, so they are refused alike.
    // checkGlobalObject follows only a declared global object: window is declared above, globalThis
    // comes with ecmaVersion, and self is declared here so that self.fetch is caught too.
    // Still uncaught: destructuring a global object (const { fetch } = window), a longer path to one
    // (document.defaultView.fetch, window.navigator.sendBeacon), and an alias of one.
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: { globals: { self: 'readonly' } },
    ignores: ['src/api/client.ts'],
    rules: {
      'no-restricted-globals': [
        'error',
        {
          globals: ['fetch', 'XMLHttpRequest', 'EventSource', 'WebSocket'].map((name) => ({
            name,
            message: CALL_THROUGH_CLIENT,
          })),
          checkGlobalObject: true,
        },
      ],
      'no-restricted-properties': [
        'error',
        { object: 'navigator', property: 'sendBeacon', message: CALL_THROUGH_CLIENT },
      ],
    },
  },
);
