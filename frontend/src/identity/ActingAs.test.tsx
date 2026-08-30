import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { ActingAsProvider, useActingAs } from './ActingAs';

/**
 * The acting identity is the only thing tying a session to its data: the repositories are in-memory
 * and organisation-scoped, so an identity that changes between reloads makes everything created
 * under the old one permanently unreachable.
 */

const STORAGE_KEY = 'hrsched.acting-as';

function Probe() {
  const { identity } = useActingAs();
  return (
    <output>
      {identity.organisation}|{identity.role}|{identity.employee ?? 'none'}
    </output>
  );
}

function renderProbe() {
  return render(
    <ActingAsProvider>
      <Probe />
    </ActingAsProvider>,
  );
}

afterEach(() => {
  window.localStorage.clear();
});

describe('the acting identity', () => {
  it('GivenNoStoredIdentity_WhenFirstRendered_ThenTheFreshOneIsPersistedImmediately', () => {
    renderProbe();

    const stored = window.localStorage.getItem(STORAGE_KEY);
    expect(stored).not.toBeNull();
    // Without this, a reload before touching the bar mints a new organisation and strands
    // everything created under the old one.
    expect(screen.getByRole('status').textContent).toContain(
      (JSON.parse(stored!) as { organisation: string }).organisation,
    );
  });

  it('GivenAnIdentityWasUsed_WhenRenderedAgain_ThenTheSameOrganisationComesBack', () => {
    const first = renderProbe();
    const before = screen.getByRole('status').textContent;
    first.unmount();

    renderProbe();

    expect(screen.getByRole('status').textContent).toBe(before);
  });

  it('GivenStoredJunk_WhenRendered_ThenItIsReplacedRatherThanTrusted', () => {
    window.localStorage.setItem(STORAGE_KEY, '{"organisation":"acme"}');

    renderProbe();

    // A stored identity missing its role would send the literal string "undefined" as X-Role and
    // fail every request while the UI showed a perfectly normal role.
    expect(screen.getByRole('status').textContent).not.toContain('acme');
    expect(screen.getByRole('status').textContent).toContain('ADMIN');
  });

  it('GivenAStoredNull_WhenRendered_ThenItDoesNotThrow', () => {
    window.localStorage.setItem(STORAGE_KEY, 'null');

    expect(() => renderProbe()).not.toThrow();
  });
});
