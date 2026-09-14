import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, identityKey, path } from './client';
import type { ActingIdentity } from './client';
import { ApiError } from './problem';

/**
 * Specification of the one module that talks to the network.
 *
 * `fetch` is stubbed rather than mocked with a library: the HTTP API is our own port, and our own
 * ports are exactly the thing it is correct to fake.
 */

const IDENTITY: ActingIdentity = {
  organisation: 'org-1',
  user: 'user-1',
  role: 'SCHEDULER',
  employee: null,
};

function respondWith(
  status: number,
  body: unknown,
  ok = status < 400,
  headers: Record<string, string> = {},
) {
  const stub = vi.fn().mockResolvedValue({
    ok,
    status,
    headers: new Headers(headers),
    text: () => Promise.resolve(body === null ? '' : JSON.stringify(body)),
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the acting identity', () => {
  it('GivenAnIdentityWithoutAnEmployee_WhenRequesting_ThenTheThreeClaimHeadersAreSent', async () => {
    const stub = respondWith(200, []);

    await api.get(IDENTITY, '/api/shifts');

    const headers = (stub.mock.calls[0]?.[1] as RequestInit).headers as Headers;
    expect(headers.get('X-Organisation')).toBe('org-1');
    expect(headers.get('X-User')).toBe('user-1');
    expect(headers.get('X-Role')).toBe('SCHEDULER');
    expect(headers.get('X-Employee')).toBeNull();
  });

  it('GivenAnIdentityCarryingAnEmployee_WhenRequesting_ThenTheEmployeeLinkIsSent', async () => {
    const stub = respondWith(204, null);

    await api.post({ ...IDENTITY, role: 'EMPLOYEE', employee: 'emp-9' }, '/api/x', { a: 1 });

    const headers = (stub.mock.calls[0]?.[1] as RequestInit).headers as Headers;
    expect(headers.get('X-Employee')).toBe('emp-9');
    expect(headers.get('Content-Type')).toBe('application/json');
  });

  it('GivenADelete_WhenRequesting_ThenItIsSentAsADeleteWithNoBody', async () => {
    const stub = respondWith(200, {});

    await api.delete(IDENTITY, '/api/rosters/1/assignments/s-1/e-1');

    const init = stub.mock.calls[0]?.[1] as RequestInit;
    expect(init.method).toBe('DELETE');
    expect(init.body).toBeUndefined();
    expect((init.headers as Headers).get('X-Role')).toBe('SCHEDULER');
  });

  it('GivenARequestWithoutABody_WhenRequesting_ThenNoContentTypeIsClaimed', async () => {
    const stub = respondWith(200, {});

    await api.post(IDENTITY, '/api/rosters/1/publication');

    const headers = (stub.mock.calls[0]?.[1] as RequestInit).headers as Headers;
    expect(headers.get('Content-Type')).toBeNull();
  });
});

describe('failures', () => {
  it('GivenARosterBreakingTheRules_WhenPublishing_ThenTheViolationsSurvive', async () => {
    respondWith(
      422,
      {
        title: 'Roster breaks the working-time rules',
        detail: 'The roster breaks 1 working-time rule(s)',
        status: 422,
        violations: [
          {
            employee: 'emp-1',
            constraint: 'maximum daily working time',
            detail: 'PT8H worked on 2026-10-05 exceeds the maximum of PT1H',
          },
        ],
      },
      false,
    );

    const failure = await api.post(IDENTITY, '/api/rosters/1/publication').catch((e: unknown) => e);

    expect(failure).toBeInstanceOf(ApiError);
    const error = failure as ApiError;
    expect(error.isRuleBreach).toBe(true);
    expect(error.violations).toHaveLength(1);
    expect(error.violations[0]?.constraint).toBe('maximum daily working time');
  });

  it('GivenAViolationOfTheWrongShape_WhenParsing_ThenItIsDiscardedRatherThanTrusted', async () => {
    respondWith(
      422,
      { detail: 'nope', violations: ['a formatted sentence', { employee: 1 }] },
      false,
    );

    const error = (await api
      .post(IDENTITY, '/api/rosters/1/publication')
      .catch((e: unknown) => e)) as ApiError;

    expect(error.violations).toHaveLength(0);
    expect(error.message).toBe('nope');
  });

  it('GivenACrossTenantRead_WhenRequesting_ThenItIsNotFoundRatherThanForbidden', async () => {
    respondWith(404, { title: 'Not found', detail: 'No such resource.' }, false);

    const error = (await api.get(IDENTITY, '/api/rosters/1').catch((e: unknown) => e)) as ApiError;

    expect(error.isNotFound).toBe(true);
    expect(error.isForbidden).toBe(false);
  });

  it('GivenARoleThatMayNotAct_WhenRequesting_ThenItIsForbidden', async () => {
    respondWith(403, { title: 'Not permitted', detail: 'Your role does not permit this.' }, false);

    const error = (await api
      .post(IDENTITY, '/api/shifts', {})
      .catch((e: unknown) => e)) as ApiError;

    expect(error.isForbidden).toBe(true);
    expect(error.message).toBe('Your role does not permit this.');
  });

  it('GivenANonJsonBody_WhenItFails_ThenTheTextIsSurfacedRatherThanAParseError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        headers: new Headers(),
        text: () => Promise.resolve('<html>Gateway exploded</html>'),
      }),
    );

    const error = (await api.get(IDENTITY, '/api/shifts').catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(500);
    expect(error.message).toContain('Gateway exploded');
  });

  it('GivenA204_WhenRequesting_ThenNothingIsParsed', async () => {
    respondWith(204, null);

    await expect(api.put(IDENTITY, '/api/employees/1/contract', { a: 1 })).resolves.toBeUndefined();
  });

  it('GivenA200CarryingSomethingOtherThanJson_WhenRequesting_ThenItFailsRatherThanCasting', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: new Headers(),
        text: () => Promise.resolve('<html>a misrouted SPA fallback</html>'),
      }),
    );

    const error = (await api.get(IDENTITY, '/api/shifts').catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    // Casting it would hand a page an object where it expects an array, and the render would throw.
    expect(error.message).toContain('not JSON');
  });
});

/**
 * The key every page uses to decide when to refetch. Nothing asserted it, and it is the one string
 * whose staleness would show as data from the wrong organisation rather than as an error.
 */
describe('the reload key', () => {
  it('GivenTheSameIdentity_WhenKeyedTwice_ThenTheKeyIsStable', () => {
    expect(identityKey(IDENTITY)).toBe(identityKey({ ...IDENTITY }));
  });

  it.each([
    ['organisation', { ...IDENTITY, organisation: 'org-2' }],
    ['user', { ...IDENTITY, user: 'user-2' }],
    ['role', { ...IDENTITY, role: 'ADMIN' as const }],
    ['employee', { ...IDENTITY, employee: 'e-1' }],
  ])('GivenTwoIdentitiesDifferingInOneClaim_WhenKeyed_ThenTheKeysDiffer', (_claim, changed) => {
    expect(identityKey(changed)).not.toBe(identityKey(IDENTITY));
  });

  /**
   * `employee: ''` and `employee: null` produce one key, and that is consistent rather than a bug:
   * `request` uses a truthiness check, so both suppress `X-Employee` and the two identities really
   * do make the same request. Changing that check to `!= null` without changing this would give two
   * different requests one cache key.
   */
  it('GivenAnEmptyEmployeeString_WhenKeyed_ThenItMatchesNoEmployeeAtAll', () => {
    expect(identityKey({ ...IDENTITY, employee: '' })).toBe(
      identityKey({ ...IDENTITY, employee: null }),
    );
  });

  it('GivenAnEmptyEmployeeString_WhenRequesting_ThenNoEmployeeHeaderIsSent', async () => {
    const stub = respondWith(200, []);

    await api.get({ ...IDENTITY, employee: '' }, '/api/shifts');

    const headers = (stub.mock.calls[0]?.[1] as RequestInit).headers as Headers;
    expect(headers.get('X-Employee')).toBeNull();
  });
});

/**
 * An identifier is one path segment and has to stay one. `fetch` resolves `..` and treats `/` as a
 * separator, so an unencoded value can quietly address a different endpoint — and the organisation
 * in the identity bar is typed by hand.
 */
describe('building a path', () => {
  it('GivenAnIdentifierContainingASlash_WhenBuildingAPath_ThenItStaysOneSegment', () => {
    expect(path`/api/organisations/${'../employees'}`).toBe('/api/organisations/..%2Femployees');
  });

  it('GivenOrdinaryIdentifiers_WhenBuildingAPath_ThenTheyArePlacedUnchanged', () => {
    expect(path`/api/employees/${'e-1'}/qualifications/${'q-1'}`).toBe(
      '/api/employees/e-1/qualifications/q-1',
    );
  });
});

describe('the trace reference', () => {
  it('GivenARefusalCarryingATraceId_WhenItIsRaised_ThenTheErrorCanBeQuotedByIt', async () => {
    respondWith(403, { detail: 'Your role does not permit this.' }, false, {
      'X-Trace-Id': '4bf92f3577b34da6a3ce929d0e0e4736',
    });

    await expect(api.post(IDENTITY, '/api/qualifications', { name: 'Forklift' })).rejects.toThrow(
      ApiError,
    );
  });

  it('GivenARefusalCarryingATraceId_WhenItIsCaught_ThenTheReferenceIsTheTraceId', async () => {
    respondWith(404, { detail: 'No such resource.' }, false, {
      'X-Trace-Id': '4bf92f3577b34da6a3ce929d0e0e4736',
    });

    const failure = await api.get(IDENTITY, '/api/rosters/1').catch((error: unknown) => error);

    expect((failure as ApiError).reference).toBe('4bf92f3577b34da6a3ce929d0e0e4736');
  });

  /**
   * A missing header means no span was active, not that some id was lost. Inventing one here would
   * hand the user a reference that appears in no log line.
   */
  it('GivenAResponseWithoutATraceId_WhenItFails_ThenThereIsNoReferenceToQuote', async () => {
    respondWith(500, { detail: 'Something broke.' }, false);

    const failure = await api.get(IDENTITY, '/api/rosters').catch((error: unknown) => error);

    expect((failure as ApiError).reference).toBeNull();
  });

  it('GivenASuccessCarryingANonJsonBody_WhenItIsRejected_ThenTheReferenceSurvives', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: new Headers({ 'X-Trace-Id': 'abc123' }),
        text: () => Promise.resolve('<!doctype html>'),
      }),
    );

    const failure = await api.get(IDENTITY, '/api/shifts').catch((error: unknown) => error);

    expect((failure as ApiError).reference).toBe('abc123');
  });
});
