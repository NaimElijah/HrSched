// @vitest-environment node
import { ESLint } from 'eslint';
import { beforeAll, describe, expect, it } from 'vitest';

/**
 * Pins the two boundaries eslint.config.js draws: only src/api/client.ts sends a request, and the
 * folders under src depend one way. `npm run lint` passing shows only that nothing crosses them
 * today. A later config block that sets one of these rules for the same files replaces its options
 * rather than adding to them, and lint stays green while the boundary is gone; these cases fail.
 *
 * Each snippet is linted as if it lived at the path given, through the project's own config. No
 * file is written.
 */

/**
 * frontend/, the directory above this file, as a filesystem path. A file URL's path puts a slash
 * before a Windows drive letter, which a filesystem path must not have. node:url's fileURLToPath
 * would do this, but the project has no Node typings to call it with.
 */
const FRONTEND_DIR = decodeURIComponent(new URL('..', import.meta.url).pathname).replace(
  /^\/(?=[A-Za-z]:)/,
  '',
);

/** Loading typescript-eslint and the config takes seconds, more under a loaded machine. */
const LINTING = { timeout: 60_000 };

const CALL_THROUGH_CLIENT = 'Call the backend through src/api/client.ts';
const ONE_WAY = 'the folders depend one way, api ← components ← auth ← pages';

let eslint: ESLint;

beforeAll(() => {
  eslint = new ESLint({ cwd: FRONTEND_DIR });
});

async function problemsIn(filePath: string, code: string) {
  const [result] = await eslint.lintText(code, { filePath });
  if (result === undefined) {
    throw new Error(`ESLint returned no result for ${filePath}`);
  }
  return result.messages.map(({ ruleId, message }) => ({ ruleId, message }));
}

function refusal(ruleId: string, wording: string) {
  return { ruleId, message: expect.stringContaining(wording) };
}

describe('sending a request', LINTING, () => {
  it.each([
    ["export const reply = fetch('/api/x');", 'no-restricted-globals'],
    ["export const reply = window.fetch('/api/x');", 'no-restricted-globals'],
    ["export const reply = globalThis.fetch('/api/x');", 'no-restricted-globals'],
    ["export const reply = self.fetch('/api/x');", 'no-restricted-globals'],
    ['export const request = new XMLHttpRequest();', 'no-restricted-globals'],
    ["export const events = new EventSource('/api/x');", 'no-restricted-globals'],
    ["export const socket = new WebSocket('ws://localhost/api/x');", 'no-restricted-globals'],
    ["export const sent = navigator.sendBeacon('/api/x');", 'no-restricted-properties'],
  ])('GivenARequestSentFromAPage_WhenLinted_ThenItIsRefused (%s)', async (code, ruleId) => {
    expect(await problemsIn('src/pages/Probe.tsx', code)).toContainEqual(
      refusal(ruleId, CALL_THROUGH_CLIENT),
    );
  });

  it('GivenFetchInTheClient_WhenLinted_ThenItIsAccepted', async () => {
    expect(await problemsIn('src/api/client.ts', "export const reply = fetch('/api/x');")).toEqual(
      [],
    );
  });

  it('GivenFetchInATest_WhenLinted_ThenItIsRefused', async () => {
    expect(
      await problemsIn('src/pages/Probe.test.tsx', "export const reply = fetch('/api/x');"),
    ).toContainEqual(refusal('no-restricted-globals', CALL_THROUGH_CLIENT));
  });
});

describe('importing across folders', LINTING, () => {
  it.each([
    ['src/components/Probe.ts', '../auth/SessionProvider'],
    ['src/auth/Probe.ts', '../pages/X'],
    ['src/api/Probe.ts', '../components/Feedback'],
  ])(
    'GivenAnImportAgainstTheDirection_WhenLinted_ThenItIsRefused (%s from %s)',
    async (filePath, source) => {
      expect(
        await problemsIn(filePath, `import { x } from '${source}';\nexport const y = x;\n`),
      ).toContainEqual(refusal('no-restricted-imports', ONE_WAY));
    },
  );

  it.each([
    ['src/pages/Probe.ts', '../components/Feedback'],
    ['src/auth/Probe.ts', '../components/Feedback'],
    ['src/components/Probe.ts', '../api/scheduling'],
  ])(
    'GivenAnImportWithTheDirection_WhenLinted_ThenItIsAccepted (%s from %s)',
    async (filePath, source) => {
      expect(
        await problemsIn(filePath, `import { x } from '${source}';\nexport const y = x;\n`),
      ).toEqual([]);
    },
  );

  it('GivenProductionCodeImportingTestSupport_WhenLinted_ThenItIsRefused', async () => {
    expect(
      await problemsIn(
        'src/pages/Probe.ts',
        "import { routeTo } from '../test-support/routeTo';\nexport const y = routeTo;\n",
      ),
    ).toContainEqual(refusal('no-restricted-imports', 'production code never imports it'));
  });
});
