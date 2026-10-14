import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, auth, path } from './client';
import { ApiError } from './problem';
import {
  currentAccessToken,
  currentSession,
  endSession,
  holdSession,
  onSessionEnded,
} from './session';
import type { Session } from './session';

/**
 * Specification of the one module that talks to the network.
 *
 * `fetch` is stubbed rather than mocked with a library: the HTTP API is our own port, and our own
 * ports are exactly the thing it is correct to fake.
 *
 * Most of this file is about renewing. A ten-minute access token means every long-lived screen will
 * meet a 401 eventually, and the rules around what happens next are not obvious: exactly one
 * renewal however many requests are waiting, exactly one retry, and no renewal at all for the
 * endpoints whose own credential is the cookie. Each of those, got wrong, is either a loop that
 * looks like an outage or a session ended by what looks to the backend like a stolen token.
 */

const A_SESSION: Session = {
  userId: 'user-1',
  kind: 'TENANT',
  organisation: 'org-1',
  role: 'SCHEDULER',
  employee: null,
  signInName: 'ada@example.com',
  passwordChangeRequired: false,
};

interface Answer {
  readonly status: number;
  readonly body: unknown;
  readonly headers?: Record<string, string>;
}

/** Answers every request with the same thing. */
function respondWith(status: number, body: unknown, headers: Record<string, string> = {}) {
  return answering(() => ({ status, body, headers }));
}

/** Answers each request by asking the test what this one should be. */
function answering(answer: (path: string, init?: RequestInit) => Answer) {
  const stub = vi.fn((path: string, init?: RequestInit) => {
    const given = answer(path, init);
    return Promise.resolve({
      ok: given.status < 400,
      status: given.status,
      headers: new Headers(given.headers ?? {}),
      text: () => Promise.resolve(given.body === null ? '' : JSON.stringify(given.body)),
    });
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

function signedIn(session: Session = A_SESSION, token = 'token-1') {
  holdSession({ accessToken: token, tokenType: 'Bearer', expiresIn: 600, session });
}

function headersOf(call: [string, (RequestInit | undefined)?] | undefined): Headers {
  return (call?.[1] as RequestInit).headers as Headers;
}

beforeEach(() => {
  endSession();
});

afterEach(() => {
  vi.unstubAllGlobals();
  endSession();
});

describe('what a request carries', () => {
  it('GivenASignedInSession_WhenRequesting_ThenTheAccessTokenIsSentAsABearer', async () => {
    signedIn();
    const stub = respondWith(200, []);

    await api.get('/api/shifts');

    expect(headersOf(stub.mock.calls[0]).get('Authorization')).toBe('Bearer token-1');
  });

  it('GivenNobodySignedIn_WhenRequesting_ThenNoAuthorizationIsSent', async () => {
    const stub = respondWith(200, []);

    await api.get('/api/shifts');

    expect(headersOf(stub.mock.calls[0]).get('Authorization')).toBeNull();
  });

  /**
   * The headers are gone, and this is the test that says so. Every route took who is acting from
   * them until the backend moved onto the token, and a client that kept sending them would be
   * offering a second, weaker answer to a question that now has one.
   */
  it('GivenASignedInTenant_WhenRequesting_ThenNoIdentityHeaderIsSent', async () => {
    signedIn({ ...A_SESSION, employee: 'emp-9' });
    const stub = respondWith(200, []);

    await api.get('/api/shifts');

    const headers = headersOf(stub.mock.calls[0]);
    expect(headers.get('X-User')).toBeNull();
    expect(headers.get('X-Role')).toBeNull();
    expect(headers.get('X-Organisation')).toBeNull();
    expect(headers.get('X-Employee')).toBeNull();
    expect(headers.get('Authorization')).toBe('Bearer token-1');
  });

  it('GivenADelete_WhenRequesting_ThenItIsSentAsADeleteWithNoBody', async () => {
    const stub = respondWith(204, null);

    await api.delete('/api/x');

    expect((stub.mock.calls[0]?.[1] as RequestInit).method).toBe('DELETE');
    expect((stub.mock.calls[0]?.[1] as RequestInit).body).toBeUndefined();
  });

  it('GivenARequestWithoutABody_WhenRequesting_ThenNoContentTypeIsClaimed', async () => {
    const stub = respondWith(200, []);

    await api.get('/api/shifts');

    expect(headersOf(stub.mock.calls[0]).get('Content-Type')).toBeNull();
  });
});

/**
 * Renewing and signing out are carried by the refresh cookie, and the backend refuses a stale
 * access token on every route before it reads the cookie. The token held when either is sent is
 * usually exactly that — renewal happens because it expired — so neither may carry it (#179).
 */
describe('what the cookie-credentialed requests carry', () => {
  it('GivenAHeldToken_WhenRenewing_ThenTheRefreshCarriesNoAuthorization', async () => {
    signedIn();
    const stub = respondWith(401, {});

    await api.get('/api/shifts').catch(() => undefined);

    const refresh = stub.mock.calls.find(([called]) => called === '/api/auth/refresh');
    expect(refresh).toBeDefined();
    expect(headersOf(refresh).get('Authorization')).toBeNull();
  });

  it('GivenAHeldToken_WhenRestoring_ThenTheRefreshCarriesNoAuthorization', async () => {
    signedIn();
    const stub = respondWith(401, {});

    await auth.restore();

    expect(headersOf(stub.mock.calls[0]).get('Authorization')).toBeNull();
  });

  it('GivenASignOut_WhenSent_ThenItCarriesTheRequestedWithHeaderAndSameOriginCredentials', async () => {
    signedIn();
    const stub = respondWith(204, null);

    await auth.signOut();

    expect(headersOf(stub.mock.calls[0]).get('X-Requested-With')).toBe('XMLHttpRequest');
    expect((stub.mock.calls[0]?.[1] as RequestInit).credentials).toBe('same-origin');
  });

  /** The header marks a cookie-credentialed request, and is sent with no other. */
  it('GivenAnOrdinaryRequest_WhenSent_ThenItCarriesNoRequestedWithHeader', async () => {
    signedIn();
    const stub = respondWith(200, []);

    await api.get('/api/shifts');

    expect(headersOf(stub.mock.calls[0]).get('X-Requested-With')).toBeNull();
  });

  it('GivenAHeldToken_WhenSigningOut_ThenItCarriesNoAuthorization', async () => {
    signedIn();
    const stub = respondWith(204, null);

    await auth.signOut();

    expect(headersOf(stub.mock.calls[0]).get('Authorization')).toBeNull();
  });

  /** Reading the session and changing the password sit under the same prefix but need the token. */
  it('GivenAHeldToken_WhenReadingTheSession_ThenTheTokenIsStillSent', async () => {
    signedIn();
    const stub = respondWith(200, A_SESSION);

    await auth.readSession();

    expect(headersOf(stub.mock.calls[0]).get('Authorization')).toBe('Bearer token-1');
  });

  it('GivenAHeldToken_WhenChangingThePassword_ThenTheTokenIsStillSent', async () => {
    signedIn();
    const stub = respondWith(200, {
      accessToken: 'token-2',
      tokenType: 'Bearer',
      expiresIn: 600,
      session: A_SESSION,
    });

    await auth.changePassword('an invented password', 'another invented password');

    expect(headersOf(stub.mock.calls[0]).get('Authorization')).toBe('Bearer token-1');
  });

  /**
   * The session surviving its access token is the whole point. The stub answers the refresh as the
   * backend does: refused when it carries a token, renewed when it carries only the cookie.
   */
  it('GivenAnExpiredAccessToken_WhenRenewingAgainstTheBackendsRule_ThenTheSessionSurvives', async () => {
    signedIn();
    let renewed = false;
    answering((path, init) => {
      const authorization = new Headers(init?.headers).get('Authorization');
      if (path === '/api/auth/refresh') {
        if (authorization !== null) {
          return { status: 401, body: { title: 'Unauthenticated' } };
        }
        renewed = true;
        return {
          status: 200,
          body: { accessToken: 'token-2', tokenType: 'Bearer', expiresIn: 600, session: A_SESSION },
        };
      }
      return renewed && authorization === 'Bearer token-2'
        ? { status: 200, body: ['a shift'] }
        : { status: 401, body: {} };
    });

    await expect(api.get('/api/shifts')).resolves.toEqual(['a shift']);

    expect(currentSession()).toEqual(A_SESSION);
  });
});

/**
 * The access token lives in a module variable and nowhere a script can read it back. A token in
 * `localStorage` is a token a cross-site scripting flaw walks away with, and one that survives the
 * tab is one that outlives the reason it was issued (ADR-028).
 */
describe('where the token is kept', () => {
  it('GivenASignIn_WhenItSucceeds_ThenNothingIsWrittenToBrowserStorage', async () => {
    respondWith(200, {
      accessToken: 'token-1',
      tokenType: 'Bearer',
      expiresIn: 600,
      session: A_SESSION,
    });
    const local = vi.spyOn(Storage.prototype, 'setItem');

    await auth.signIn('ada@example.com', 'an invented password for a test');

    expect(local).not.toHaveBeenCalled();
    expect(currentAccessToken()).toBe('token-1');
    local.mockRestore();
  });

  it('GivenASignOut_WhenItAnswers_ThenTheTokenAndSessionAreForgotten', async () => {
    signedIn();
    respondWith(204, null);

    await auth.signOut();

    expect(currentAccessToken()).toBeNull();
    expect(currentSession()).toBeNull();
  });

  /** Somebody who asked to sign out must not stay signed in because the network was down. */
  it('GivenASignOutThatFails_WhenItThrows_ThenTheTokenIsForgottenAnyway', async () => {
    signedIn();
    respondWith(500, { detail: 'gone' });

    await expect(auth.signOut()).rejects.toBeInstanceOf(ApiError);

    expect(currentAccessToken()).toBeNull();
  });
});

describe('renewing a session', () => {
  it('GivenAnExpiredAccessToken_WhenRequesting_ThenTheSessionIsRefreshedOnceAndTheRequestRetried', async () => {
    signedIn();
    let refreshed = false;
    const stub = answering((path) => {
      if (path === '/api/auth/refresh') {
        refreshed = true;
        return {
          status: 200,
          body: {
            accessToken: 'token-2',
            tokenType: 'Bearer',
            expiresIn: 600,
            session: A_SESSION,
          },
        };
      }
      return refreshed ? { status: 200, body: ['a shift'] } : { status: 401, body: {} };
    });

    await expect(api.get('/api/shifts')).resolves.toEqual(['a shift']);

    expect(stub.mock.calls.map(([called]) => called)).toEqual([
      '/api/shifts',
      '/api/auth/refresh',
      '/api/shifts',
    ]);
    expect(currentAccessToken()).toBe('token-2');
  });

  /**
   * Two tabs renewing with one cookie is what reuse detection is built to punish, and two requests
   * in one tab would be the same thing. One renewal is shared by everything waiting on it.
   */
  it('GivenTwoRequestsFailingTogether_WhenRefreshing_ThenOnlyOneRefreshIsSent', async () => {
    signedIn();
    let refreshed = false;
    const stub = answering((path) => {
      if (path === '/api/auth/refresh') {
        refreshed = true;
        return {
          status: 200,
          body: {
            accessToken: 'token-2',
            tokenType: 'Bearer',
            expiresIn: 600,
            session: A_SESSION,
          },
        };
      }
      return refreshed ? { status: 200, body: [] } : { status: 401, body: {} };
    });

    await Promise.all([api.get('/api/shifts'), api.get('/api/rosters')]);

    expect(stub.mock.calls.filter(([called]) => called === '/api/auth/refresh')).toHaveLength(1);
  });

  it('GivenARefreshThatFails_WhenRequesting_ThenTheSessionEndsAndTheOriginalErrorIsThrown', async () => {
    signedIn();
    let ended = false;
    respondWith(401, { detail: 'A valid access token is required.' });
    const stop = onSessionEnded(() => {
      ended = true;
    });

    const error = (await api.get('/api/shifts').catch((e: unknown) => e)) as ApiError;

    expect(error.isUnauthenticated).toBe(true);
    expect(currentAccessToken()).toBeNull();
    expect(ended).toBe(true);
    stop();
  });

  /**
   * The backend refuses a cookie only with a 401. A 502 comes from whatever stands in front of it
   * with nothing behind, which has decided nothing about the cookie (#139).
   */
  it('GivenARefreshAnsweredByAGatewayWithNoBackend_WhenRequesting_ThenTheSessionIsKeptAndThatFailureThrown', async () => {
    signedIn();
    let ended = false;
    answering((path) =>
      path === '/api/auth/refresh' ? { status: 502, body: null } : { status: 401, body: {} },
    );
    const stop = onSessionEnded(() => {
      ended = true;
    });

    const error = (await api.get('/api/shifts').catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(502);
    expect(currentAccessToken()).not.toBeNull();
    expect(ended).toBe(false);
    stop();
  });

  it('GivenARefreshAnsweredByAGatewayWithNoBackend_WhenRestoring_ThenItIsThrownRatherThanReadAsNoSession', async () => {
    respondWith(502, null);

    await expect(auth.restore()).rejects.toBeInstanceOf(ApiError);
  });

  /**
   * Changing the password is credentialed by the access token, and the backend answers a wrong
   * current password with a 403 precisely so that a 401 here means the token. Somebody who spends
   * more than a token's lifetime on the change-password screen must still be able to submit it.
   */
  it('GivenAnExpiredAccessToken_WhenChangingThePassword_ThenItIsRenewedAndRetried', async () => {
    signedIn();
    let renewed = false;
    const stub = answering((path, init) => {
      if (path === '/api/auth/refresh') {
        renewed = true;
        return {
          status: 200,
          body: { accessToken: 'token-2', tokenType: 'Bearer', expiresIn: 600, session: A_SESSION },
        };
      }
      const authorization = new Headers(init?.headers).get('Authorization');
      return renewed && authorization === 'Bearer token-2'
        ? {
            status: 200,
            body: {
              accessToken: 'token-3',
              tokenType: 'Bearer',
              expiresIn: 600,
              session: A_SESSION,
            },
          }
        : { status: 401, body: {} };
    });

    await auth.changePassword('an invented password', 'another invented password');

    expect(stub.mock.calls.map(([called]) => called)).toEqual([
      '/api/auth/password',
      '/api/auth/refresh',
      '/api/auth/password',
    ]);
    expect(currentAccessToken()).toBe('token-3');
  });

  it('GivenAnExpiredAccessToken_WhenReadingTheSession_ThenItIsRenewedAndRetried', async () => {
    signedIn();
    let renewed = false;
    const stub = answering((path) => {
      if (path === '/api/auth/refresh') {
        renewed = true;
        return {
          status: 200,
          body: { accessToken: 'token-2', tokenType: 'Bearer', expiresIn: 600, session: A_SESSION },
        };
      }
      return renewed ? { status: 200, body: A_SESSION } : { status: 401, body: {} };
    });

    await expect(auth.readSession()).resolves.toEqual(A_SESSION);

    expect(stub.mock.calls.filter(([called]) => called === '/api/auth/session')).toHaveLength(2);
  });

  /** Signing out is carried by the cookie, so its 401 says nothing a renewal could change. */
  it('GivenASignOutAnswering401_WhenRequesting_ThenNoRefreshIsAttempted', async () => {
    signedIn();
    const stub = respondWith(401, {});

    await expect(auth.signOut()).rejects.toBeInstanceOf(ApiError);

    expect(stub.mock.calls.filter(([called]) => called === '/api/auth/refresh')).toHaveLength(0);
  });

  /** A 401 from signing in is the answer, not a hint to go and renew something. */
  it('GivenAnAuthEndpointAnswering401_WhenRequesting_ThenNoRefreshIsAttempted', async () => {
    const stub = respondWith(401, { detail: 'Those credentials were not accepted.' });

    await expect(auth.signIn('ada@example.com', 'wrong')).rejects.toBeInstanceOf(ApiError);

    expect(stub.mock.calls.filter(([called]) => called === '/api/auth/refresh')).toHaveLength(0);
  });

  it('GivenARetryThatIsRefusedAgain_WhenRequesting_ThenItIsNotRetriedTwice', async () => {
    signedIn();
    const stub = answering((path) =>
      path === '/api/auth/refresh'
        ? {
            status: 200,
            body: {
              accessToken: 'token-2',
              tokenType: 'Bearer',
              expiresIn: 600,
              session: A_SESSION,
            },
          }
        : { status: 401, body: {} },
    );

    await expect(api.get('/api/shifts')).rejects.toBeInstanceOf(ApiError);

    expect(stub.mock.calls.filter(([called]) => called === '/api/shifts')).toHaveLength(2);
  });

  it('GivenARefresh_WhenSent_ThenItCarriesTheRequestedWithHeader', async () => {
    signedIn();
    const stub = answering((path) =>
      path === '/api/auth/refresh' ? { status: 401, body: {} } : { status: 401, body: {} },
    );

    await api.get('/api/shifts').catch(() => undefined);

    const refresh = stub.mock.calls.find(([called]) => called === '/api/auth/refresh');
    expect(headersOf(refresh).get('X-Requested-With')).toBe('XMLHttpRequest');
    expect((refresh?.[1] as RequestInit).credentials).toBe('same-origin');
  });

  it('GivenWebLocks_WhenRefreshing_ThenTheRefreshRunsInsideTheLock', async () => {
    signedIn();
    const inside: string[] = [];
    vi.stubGlobal('navigator', {
      locks: {
        request: async (name: string, run: () => Promise<unknown>) => {
          inside.push(name);
          return run();
        },
      },
    });
    answering((path) =>
      path === '/api/auth/refresh' ? { status: 401, body: {} } : { status: 401, body: {} },
    );

    await api.get('/api/shifts').catch(() => undefined);

    expect(inside).toEqual(['hrsched.refresh']);
  });
});

describe('what a failure carries', () => {
  it('GivenARosterBreakingTheRules_WhenPublishing_ThenTheViolationsSurvive', async () => {
    respondWith(422, {
      detail: 'Refused',
      violations: [{ employee: 'e-1', constraint: 'Daily maximum', detail: 'over by an hour' }],
    });

    const error = (await api
      .post('/api/rosters/1/publication')
      .catch((e: unknown) => e)) as ApiError;

    expect(error.isRuleBreach).toBe(true);
    expect(error.violations).toHaveLength(1);
  });

  it('GivenAViolationOfTheWrongShape_WhenParsing_ThenItIsDiscardedRatherThanTrusted', async () => {
    respondWith(422, {
      detail: 'nope',
      violations: ['a formatted sentence', { employee: 1 }],
    });

    const error = (await api
      .post('/api/rosters/1/publication')
      .catch((e: unknown) => e)) as ApiError;

    expect(error.violations).toHaveLength(0);
    expect(error.message).toBe('nope');
  });

  /**
   * A proxy or gateway with nothing behind it answers with no body. The failure shown must still
   * say something, or the person sees an empty alert.
   */
  it('GivenAFailureWithNoBody_WhenRequesting_ThenTheMessageNamesTheStatus', async () => {
    respondWith(502, null);

    const error = (await api.get('/api/shifts').catch((e: unknown) => e)) as ApiError;

    expect(error.message).toBe('The server returned 502.');
  });

  it('GivenACrossTenantRead_WhenRequesting_ThenItIsNotFoundRatherThanForbidden', async () => {
    respondWith(404, { title: 'Not found', detail: 'No such resource.' });

    const error = (await api.get('/api/rosters/1').catch((e: unknown) => e)) as ApiError;

    expect(error.isNotFound).toBe(true);
    expect(error.isForbidden).toBe(false);
  });

  it('GivenARoleThatMayNotAct_WhenRequesting_ThenItIsForbidden', async () => {
    respondWith(403, { title: 'Not permitted', detail: 'Your role does not permit this.' });

    const error = (await api.post('/api/shifts', {}).catch((e: unknown) => e)) as ApiError;

    expect(error.isForbidden).toBe(true);
    expect(error.isUnauthenticated).toBe(false);
  });

  it('GivenANonJsonBody_WhenItFails_ThenTheTextIsSurfacedRatherThanAParseError', async () => {
    const stub = vi.fn(() =>
      Promise.resolve({
        ok: false,
        status: 502,
        headers: new Headers(),
        text: () => Promise.resolve('<html>bad gateway</html>'),
      }),
    );
    vi.stubGlobal('fetch', stub);

    const error = (await api.get('/api/shifts').catch((e: unknown) => e)) as ApiError;

    expect(error.message).toContain('bad gateway');
  });

  it('GivenA204_WhenRequesting_ThenNothingIsParsed', async () => {
    respondWith(204, null);

    await expect(api.delete('/api/x')).resolves.toBeUndefined();
  });

  it('GivenA200CarryingSomethingOtherThanJson_WhenRequesting_ThenItFailsRatherThanCasting', async () => {
    const stub = vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        headers: new Headers(),
        text: () => Promise.resolve('not json'),
      }),
    );
    vi.stubGlobal('fetch', stub);

    const error = (await api.get('/api/shifts').catch((e: unknown) => e)) as ApiError;

    expect(error.message).toContain('not JSON');
  });
});

/**
 * An identifier is one path segment and has to stay one. `fetch` resolves `..` and treats `/` as a
 * separator, so an unencoded value can quietly address a different endpoint.
 */
describe('building a path', () => {
  it('GivenAnIdentifierContainingASlash_WhenBuildingAPath_ThenItStaysOneSegment', () => {
    expect(path`/api/employees/${'a/b'}/qualifications`).toBe(
      '/api/employees/a%2Fb/qualifications',
    );
  });

  it('GivenOrdinaryIdentifiers_WhenBuildingAPath_ThenTheyArePlacedUnchanged', () => {
    expect(path`/api/rosters/${'roster-1'}`).toBe('/api/rosters/roster-1');
  });

  it.each(['.', '..'])('GivenADotSegment_WhenBuildingAPath_ThenItIsRefused', (identifier) => {
    expect(() => path`/api/employees/${identifier}`).toThrow();
  });
});

describe('the trace id', () => {
  it('GivenARefusalCarryingATraceId_WhenItIsCaught_ThenTheReferenceIsTheTraceId', async () => {
    respondWith(403, { detail: 'no' }, { 'X-Trace-Id': 'trace-9' });

    const error = (await api.get('/api/shifts').catch((e: unknown) => e)) as ApiError;

    expect(error.reference).toBe('trace-9');
  });

  it('GivenAResponseWithoutATraceId_WhenItFails_ThenThereIsNoReferenceToQuote', async () => {
    respondWith(403, { detail: 'no' });

    const error = (await api.get('/api/shifts').catch((e: unknown) => e)) as ApiError;

    expect(error.reference).toBeNull();
  });

  it('GivenASuccessCarryingANonJsonBody_WhenItIsRejected_ThenTheReferenceSurvives', async () => {
    const stub = vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        headers: new Headers({ 'X-Trace-Id': 'trace-7' }),
        text: () => Promise.resolve('not json'),
      }),
    );
    vi.stubGlobal('fetch', stub);

    const error = (await api.get('/api/shifts').catch((e: unknown) => e)) as ApiError;

    expect(error.reference).toBe('trace-7');
  });
});
