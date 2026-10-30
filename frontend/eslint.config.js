import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';

const CALL_THROUGH_CLIENT = 'Call the backend through src/api/client.ts, the only module that may.';

// esquery patterns for destructuring a request primitive off a global object.
const GLOBAL_OBJECT = '/^(window|globalThis|self)$/';
const REQUEST_PRIMITIVE = '/^(fetch|XMLHttpRequest|EventSource|WebSocket)$/';

const TEST_CODE = ['src/**/*.test.{ts,tsx}', 'src/test-setup.ts'];
const NOT_IN_PRODUCTION = 'test-support is for tests; production code never imports it.';

// The folders under src depend one way, each importing only those before it in this list. auth
// imports components/Feedback, so a shared hook in components that read the session would close a
// cycle. Test code may reach across, as a test sets up the screen it renders through test-support.
// Only a static import of a sibling folder is matched: import() and a path through ../../ get past.
const FOLDERS = ['api', 'components', 'auth', 'pages'];
const DIRECTION = FOLDERS.join(' ← ');

const folderBoundaries = FOLDERS.map((folder, index) => {
  const earlier = FOLDERS.slice(0, index).map((name) => `src/${name}`);
  const later = FOLDERS.slice(index + 1).map((name) => `../${name}`);
  const allowed = earlier.length > 0 ? `only ${earlier.join(' and ')}` : 'no other folder';
  const patterns = [{ group: ['../test-support'], message: NOT_IN_PRODUCTION }];
  if (later.length > 0) {
    patterns.unshift({
      group: later,
      message: `src/${folder} may import ${allowed}: the folders depend one way, ${DIRECTION}.`,
    });
  }
  return {
    files: [`src/${folder}/**/*.{ts,tsx}`],
    ignores: TEST_CODE,
    rules: { 'no-restricted-imports': ['error', { patterns }] },
  };
});

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
    // Destructuring one off a global object (const { fetch } = window) is refused by syntax, since
    // neither rule above sees it. Still uncaught: a longer path to a global object
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
      'no-restricted-syntax': [
        'error',
        {
          selector: `VariableDeclarator[init.name=${GLOBAL_OBJECT}] > ObjectPattern > Property[key.name=${REQUEST_PRIMITIVE}]`,
          message: CALL_THROUGH_CLIENT,
        },
        {
          selector: `AssignmentExpression[right.name=${GLOBAL_OBJECT}] > ObjectPattern > Property[key.name=${REQUEST_PRIMITIVE}]`,
          message: CALL_THROUGH_CLIENT,
        },
      ],
    },
  },
  ...folderBoundaries,
  {
    files: ['src/*.{ts,tsx}'],
    ignores: TEST_CODE,
    rules: {
      'no-restricted-imports': [
        'error',
        { patterns: [{ group: ['./test-support'], message: NOT_IN_PRODUCTION }] },
      ],
    },
  },
);
