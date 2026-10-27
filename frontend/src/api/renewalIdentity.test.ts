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
const ROSTERS = '/api/rosters';
const REFRESH = '/api/auth/refresh';

/** The paths the backend answers to the renewed token, and refuses to any other. */
const PROTECTED: ReadonlySet<string> = new Set([SHIFTS, ROSTERS]);

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

/** An answer the test holds back until it opens the gate, to order what the tab sees. */
function aGate() {
  let open: () => void = () => undefined;
  const opened = new Promise<void>((resolve) => {
    open = resolve;
  });
  return { opened, open };
}

/**
 * A backend that renews the cookie as `renewed`, and answers the shifts and rosters lists to that
 * token alone.
 *
 * Accepting the renewed token is what the real backend would do, which is the point: if the tab
 * retried with another account's token the list would come back, and the leak would be a success.
 *
 * @param renewed what the refresh cookie renews as
 * @param holdFirstAnswer per path, a gate the first answer to that path waits for
 */
function aBackendRenewingAs(
  renewed: SignedIn,
  holdFirstAnswer: Readonly<Record<string, Promise<void>>> = {},
) {
  const stillHeld = new Set(Object.keys(holdFirstAnswer));
  const stub = vi.fn(async (path: string, init?: RequestInit) => {
    if (stillHeld.delete(path)) {
      await holdFirstAnswer[path];
    }
    const bearer = new Headers(init?.headers).get('Authorization');
    const answer =
      path === REFRESH
        ? { status: 200, body: renewed }
        : PROTECTED.has(path) && bearer === `Bearer ${renewed.accessToken}`
          ? { status: 200, body: ['a shift'] }
          : { status: 401, body: { detail: 'A valid access token is required.' } };
    return {
      ok: answer.status < 400,
      status: answer.status,
      headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify(answer.body)),
    };
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

/** Channels a test opened to play another tab, closed before the next test. */
const opened: BroadcastChannel[] = [];

afterEach(() => {
  for (const channel of opened.splice(0)) {
    channel.close();
  }
  vi.unstubAllGlobals();
  endSession();
});

/** Whether a request was refused as unauthenticated, rather than answered or failed otherwise. */
function refusedAsUnauthenticated(outcome: PromiseSettledResult<unknown>): boolean {
  return (
    outcome.status === 'rejected' &&
    outcome.reason instanceof ApiError &&
    outcome.reason.isUnauthenticated
  );
}

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

/**
 * A renewal is checked against the account the request was sent as, not against whatever is held
 * when its 401 comes back. By then the session may already have ended — through another request's
 * renewal, or another tab — and "nothing held" would read as a restore that may adopt anybody.
 */
describe('a renewal anchored to the account the request was sent as', () => {
  it('GivenTwoRequestsSentAsA_WhenTheSecondIsRefusedAfterTheFirstsRenewalAnsweredB_ThenNeitherCarriesBsToken', async () => {
    holdSession(signedInAs(ACCOUNT_A, 'token-a'));
    const rostersRefused = aGate();
    const stub = aBackendRenewingAs(signedInAs(ACCOUNT_B, 'token-b'), {
      [ROSTERS]: rostersRefused.opened,
    });

    const first = api.get(SHIFTS);
    const second = api.get(ROSTERS);
    const [firstOutcome] = await Promise.allSettled([first]);
    rostersRefused.open();
    const [secondOutcome] = await Promise.allSettled([second]);

    expect(bearersSentTo(stub, SHIFTS)).not.toContain('Bearer token-b');
    expect(bearersSentTo(stub, ROSTERS), 'the request sent as A, retried as B').not.toContain(
      'Bearer token-b',
    );
    expect(refusedAsUnauthenticated(firstOutcome as PromiseSettledResult<unknown>)).toBe(true);
    expect(secondOutcome, 'the second request should be refused as unauthenticated').toMatchObject({
      status: 'rejected',
    });
    expect(refusedAsUnauthenticated(secondOutcome as PromiseSettledResult<unknown>)).toBe(true);
    expect(currentSession()).toBeNull();
  });

  it('GivenARequestSentAsAInFlight_WhenAnotherTabEndsTheSessionAndTheRenewalAnswersB_ThenItNeverCarriesBsToken', async () => {
    holdSession(signedInAs(ACCOUNT_A, 'token-a'));
    const shiftsRefused = aGate();
    const stub = aBackendRenewingAs(signedInAs(ACCOUNT_B, 'token-b'), {
      [SHIFTS]: shiftsRefused.opened,
    });
    const inFlight = api.get(SHIFTS);
    const anotherTab = new BroadcastChannel('hrsched.session');
    opened.push(anotherTab);

    anotherTab.postMessage('ended');
    await vi.waitFor(() => expect(currentSession(), 'the other tab ending the session').toBeNull());
    shiftsRefused.open();
    const [outcome] = await Promise.allSettled([inFlight]);

    expect(bearersSentTo(stub, SHIFTS)).not.toContain('Bearer token-b');
    expect(outcome, 'the request should be refused as unauthenticated').toMatchObject({
      status: 'rejected',
    });
    expect(refusedAsUnauthenticated(outcome as PromiseSettledResult<unknown>)).toBe(true);
    expect(currentSession()).toBeNull();
  });

  it('GivenTwoRequestsRefusedTogether_WhenTheirSharedRenewalAnswersB_ThenBothAreRefusedAndNeitherCarriesBsToken', async () => {
    holdSession(signedInAs(ACCOUNT_A, 'token-a'));
    const renewalAnswered = aGate();
    const stub = aBackendRenewingAs(signedInAs(ACCOUNT_B, 'token-b'), {
      [REFRESH]: renewalAnswered.opened,
    });

    const outcomes = Promise.allSettled([api.get(SHIFTS), api.get(ROSTERS)]);
    await vi.waitFor(() => expect(bearersSentTo(stub, REFRESH)).toHaveLength(1));
    renewalAnswered.open();
    const settled = await outcomes;

    expect(settled.map(refusedAsUnauthenticated), 'both refused as unauthenticated').toEqual([
      true,
      true,
    ]);
    expect(bearersSentTo(stub, SHIFTS)).not.toContain('Bearer token-b');
    expect(bearersSentTo(stub, ROSTERS)).not.toContain('Bearer token-b');
    expect(bearersSentTo(stub, REFRESH), 'one renewal shared by both').toHaveLength(1);
    expect(currentSession()).toBeNull();
  });
});
