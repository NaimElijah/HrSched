import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * A browser without `BroadcastChannel` still signs out.
 *
 * Telling other tabs is the part that degrades: without the channel a sign-out stays in its own
 * tab, which is how this app behaved before #250. Ending the session here must not depend on it.
 * The module reads the global when it loads, so the test loads a fresh copy after removing it.
 */

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetModules();
});

async function aSessionModuleWithoutBroadcastChannel() {
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.resetModules();
  return import('./session');
}

describe('a browser without BroadcastChannel', () => {
  it('GivenNoBroadcastChannel_WhenASessionIsHeldAndEnded_ThenNothingThrowsAndTheSessionIsForgotten', async () => {
    const session = await aSessionModuleWithoutBroadcastChannel();
    session.holdSession({
      accessToken: 'token-1',
      tokenType: 'Bearer',
      expiresIn: 600,
      session: {
        userId: 'user-1',
        kind: 'TENANT',
        organisation: 'org-1',
        role: 'ADMIN',
        employee: null,
        signInName: 'ada@example.com',
        passwordChangeRequired: false,
      },
    });

    expect(() => session.endSession()).not.toThrow();

    expect(session.currentSession()).toBeNull();
    expect(session.currentAccessToken()).toBeNull();
  });
});
