import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

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
    // 401. Tests are exempt because they stand in for the network, not because they talk to it.
    // checkGlobalObject follows only a declared global object: window is declared above, globalThis
    // comes with ecmaVersion, and self is declared here so that self.fetch is caught too.
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: { globals: { self: 'readonly' } },
    ignores: ['src/api/client.ts', 'src/**/*.test.{ts,tsx}', 'src/test-support/**'],
    rules: {
      'no-restricted-globals': [
        'error',
        {
          globals: [
            {
              name: 'fetch',
              message: 'Call the backend through src/api/client.ts, the only module that may.',
            },
          ],
          checkGlobalObject: true,
        },
      ],
    },
  },
);
