import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setActingIdentity } from './client';
import type { ActingIdentity } from './client';
import { workforce } from './workforce';

/**
 * Only what the pages cannot show: that an identifier interpolated into a path is encoded at the
 * call site, rather than trusted to be a UUID because the server happened to send one.
 */

const IDENTITY: ActingIdentity = {
  organisation: 'org-1',
  user: 'user-1',
  role: 'ADMIN',
  employee: null,
};

beforeEach(() => {
  setActingIdentity(IDENTITY);
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
