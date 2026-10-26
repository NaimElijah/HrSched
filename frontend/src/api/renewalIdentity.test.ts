import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, auth } from './client';
import { ApiError } from './problem';
import { currentAccessToken, currentSession, endSession, holdSession } from './session';
import type { Session, SignedIn } from './session';

/**
 * A renewal must never change who a tab is acting as.
 *
 * The refresh cookie is shared by every tab of the browser, so the account it renews is whichever
 * one signed in last, in any tab. A tab that adopted that answer without looking would go on acting
 * — with the screens, the data and the edits of one account still on it — as another (#250). A
 * renewal answered with somebody else's session ends this tab's session instead.
 *
 * Restoring is different: a tab that holds nothing is asking whose the cookie is, and any answer
 * is the right one.
 *
 * Every account and token here is invented.
 */

const SHIFTS = '/api/shifts';

const ACCOUNT_A: Session = {
  userId: 'user-a',
  kind: 'TENANT',
  organisation: 'org-1',
  role: 'SCHEDULER',
  employee: null,
  signInName: 'a@example.com',
  passwordChangeRequired: false,
};

const ACCOUNT_B: Session = {
  ...ACCOUNT_A,
  userId: 'user-b',
  role: 'ADMIN',
  signInName: 'b@example.com',
};

function signedInAs(session: Session, accessToken: string): SignedIn {
  return { accessToken, tokenType: 'Bearer', expiresIn: 600, session };
}

/**
 * A backend that renews the cookie as `renewed`, and answers the shifts list to that token alone.
 *
 * Accepting the renewed token is what the real backend would do, which is the point: if the tab
 * retried with another account's token the list would come back, and the leak would be a success.
 */
function aBackendRenewingAs(renewed: SignedIn) {
  const stub = vi.fn((path: string, init?: RequestInit) => {
    const bearer = new Headers(init?.headers).get('Authorization');
    const answer =
      path === '/api/auth/refresh'
        ? { status: 200, body: renewed }
        : path === SHIFTS && bearer === `Bearer ${renewed.accessToken}`
          ? { status: 200, body: ['a shift'] }
          : { status: 401, body: { detail: 'A valid access token is required.' } };
    return Promise.resolve({
      ok: answer.status < 400,
      status: answer.status,
      headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify(answer.body)),
    });
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

function bearersSentTo(stub: ReturnType<typeof aBackendRenewingAs>, path: string) {
  return stub.mock.calls
    .filter(([called]) => called === path)
    .map(([, init]) => new Headers(init?.headers).get('Authorization'));
}

beforeEach(() => {
  endSession();
});

afterEach(() => {
  vi.unstubAllGlobals();
  endSession();
});

describe('a renewal answered with another account', () => {
  it('GivenAccountAHeld_WhenTheRenewalAnswersWithAccountB_ThenTheRequestIsRefusedAsUnauthenticated', async () => {
    holdSession(signedInAs(ACCOUNT_A, 'token-a'));
    aBackendRenewingAs(signedInAs(ACCOUNT_B, 'token-b'));

    const outcome = await api.get(SHIFTS).then(
      (answered: unknown) => ({ answered }),
      (error: unknown) => ({ error }),
    );

    expect(outcome, 'the request should be refused, not answered as account B').toHaveProperty(
      'error',
    );
    const { error } = outcome as { error: unknown };
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).isUnauthenticated).toBe(true);
  });

  it('GivenAccountAHeld_WhenTheRenewalAnswersWithAccountB_ThenTheSessionIsEnded', async () => {
    holdSession(signedInAs(ACCOUNT_A, 'token-a'));
    aBackendRenewingAs(signedInAs(ACCOUNT_B, 'token-b'));

    await api.get(SHIFTS).catch(() => undefined);

    expect(currentSession(), 'neither account should still be held').toBeNull();
    expect(currentAccessToken()).toBeNull();
  });

  it('GivenAccountAHeld_WhenTheRenewalAnswersWithAccountB_ThenNoRequestCarriesAccountBsToken', async () => {
    holdSession(signedInAs(ACCOUNT_A, 'token-a'));
    const stub = aBackendRenewingAs(signedInAs(ACCOUNT_B, 'token-b'));

    await api.get(SHIFTS).catch(() => undefined);

    expect(bearersSentTo(stub, SHIFTS)).not.toContain('Bearer token-b');
  });
});

describe('a renewal answered with the same account', () => {
  it('GivenAccountAHeld_WhenTheRenewalAnswersWithAccountA_ThenTheRequestIsRetriedWithTheNewToken', async () => {
    holdSession(signedInAs(ACCOUNT_A, 'token-a'));
    const stub = aBackendRenewingAs(signedInAs(ACCOUNT_A, 'token-a2'));

    await expect(api.get(SHIFTS)).resolves.toEqual(['a shift']);

    expect(bearersSentTo(stub, SHIFTS)).toEqual(['Bearer token-a', 'Bearer token-a2']);
    expect(currentSession()).toEqual(ACCOUNT_A);
  });
});

describe('restoring with nothing held', () => {
  it('GivenNoSessionHeld_WhenRestoring_ThenTheCookiesAccountIsAdopted', async () => {
    aBackendRenewingAs(signedInAs(ACCOUNT_B, 'token-b'));

    await expect(auth.restore()).resolves.toBe(true);

    expect(currentSession()).toEqual(ACCOUNT_B);
    expect(currentAccessToken()).toBe('token-b');
  });
});
