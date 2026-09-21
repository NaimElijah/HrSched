import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endSession, holdSession } from './session';
import type { Session } from './session';
import { workforce } from './workforce';

/**
 * Only what the pages cannot show: that an identifier interpolated into a path is encoded at the
 * call site, rather than trusted to be a UUID because the server happened to send one.
 */

const A_SESSION: Session = {
  userId: 'user-1',
  kind: 'TENANT',
  organisation: 'org-1',
  role: 'ADMIN',
  employee: null,
  signInName: 'ada@example.com',
  passwordChangeRequired: false,
};

beforeEach(() => {
  holdSession({
    accessToken: 'a-test-access-token',
    tokenType: 'Bearer',
    expiresIn: 600,
    session: A_SESSION,
  });
});

function accepting() {
  const stub = vi.fn().mockResolvedValue({
    ok: true,
    status: 204,
    headers: new Headers(),
    text: () => Promise.resolve(''),
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

afterEach(() => {
  vi.unstubAllGlobals();
  endSession();
});

describe('addressing an employee', () => {
  it('GivenAnIdentifierContainingASlash_WhenGranting_ThenTheRequestStaysOnThatEmployee', async () => {
    const stub = accepting();

    await workforce.grantQualification('../../organisations', 'q-1');

    expect(stub.mock.calls[0]?.[0]).toBe(
      '/api/employees/..%2F..%2Forganisations/qualifications/q-1',
    );
  });
});
