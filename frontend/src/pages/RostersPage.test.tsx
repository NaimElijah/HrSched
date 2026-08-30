import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ActingAsProvider } from '../identity/ActingAs';
import { RostersPage } from './RostersPage';

/**
 * The publication refusal is the behaviour this product is sold on, so it is asserted on directly:
 * a roster that breaks the rules must show every rule it broke, and must not be dismissible as a
 * generic error.
 */

const SPAN = { from: '2026-10-05T00:00:00.000Z', to: '2026-10-12T00:00:00.000Z' };

const ROSTER = {
  id: 'roster-1',
  // Deliberately outside SPAN: a roster must resolve against its own period, not the span the
  // scheduler happens to be looking at.
  start: '2026-11-02T00:00:00Z',
  end: '2026-11-09T00:00:00Z',
  status: 'DRAFT',
  assignments: [{ shift: 'shift-1', employee: 'emp-1' }],
  validatedAgainstConstraintVersion: null,
};

const SHIFT = {
  id: 'shift-1',
  start: '2026-11-03T08:00:00Z',
  end: '2026-11-03T16:00:00Z',
  requiredHeadcount: 1,
  requiredQualifications: [],
};

const ADA = { id: 'emp-1', name: 'Ada', qualifications: [] };

function routeTo(handlers: Record<string, { status: number; body: unknown }>) {
  const stub = vi.fn((path: string) => {
    const key = Object.keys(handlers).find((candidate) => path.startsWith(candidate));
    const match = key === undefined ? { status: 404, body: {} } : handlers[key]!;
    return Promise.resolve({
      ok: match.status < 400,
      status: match.status,
      headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify(match.body)),
    });
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

function renderPage() {
  return render(
    <ActingAsProvider>
      <RostersPage span={SPAN} />
    </ActingAsProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('the rosters page', () => {
  it('GivenARosterOutsideTheSelectedSpan_WhenOpened_ThenItsShiftsStillResolve', async () => {
    const stub = routeTo({
      '/api/rosters': { status: 200, body: [ROSTER] },
      '/api/shifts': { status: 200, body: [SHIFT] },
      '/api/employees': { status: 200, body: [ADA] },
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Open' }));

    // Joined client-side against the roster's own period, not the span on screen.
    const detail = await screen.findByText(/Ada —.*2026/);
    expect(detail.textContent).not.toContain('shift-1');

    const shiftCalls = stub.mock.calls
      .map((call) => String(call[0]))
      .filter((path) => path.startsWith('/api/shifts'));
    expect(shiftCalls.at(-1)).toContain(encodeURIComponent(ROSTER.start));
  });

  it('GivenARosterThatBreaksTheRules_WhenPublishing_ThenEveryViolationIsShown', async () => {
    routeTo({
      '/api/rosters/roster-1/publication': {
        status: 422,
        body: {
          detail: 'The roster breaks 1 working-time rule(s)',
          violations: [
            {
              employee: 'emp-1',
              constraint: 'maximum daily working time',
              detail: 'PT8H worked on 2026-10-05 exceeds the maximum of PT1H',
            },
          ],
        },
      },
      '/api/rosters': { status: 200, body: [ROSTER] },
      '/api/shifts': { status: 200, body: [SHIFT] },
      '/api/employees': { status: 200, body: [ADA] },
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Publish' }));

    const alert = await screen.findByRole('alert');
    // Resolved to a name: the reason the 422 payload is structured at all.
    expect(alert).toHaveTextContent('Ada');
    expect(alert).toHaveTextContent('maximum daily working time');
    expect(alert).toHaveTextContent('exceeds the maximum of PT1H');
    expect(alert).toHaveTextContent('There is no override');
  });

  it('GivenAnEmptyDraft_WhenOpened_ThenTheGapIsExplainedRatherThanLeftBlank', async () => {
    routeTo({
      '/api/rosters': {
        status: 200,
        body: [{ ...ROSTER, assignments: [] }],
      },
      '/api/shifts': { status: 200, body: [] },
      '/api/employees': { status: 200, body: [] },
    });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Open' }));

    expect(await screen.findByText(/left short rather than filled illegally/)).toBeInTheDocument();
  });

  it('GivenARoleThatMayNotRoster_WhenGenerating_ThenTheRefusalIsShown', async () => {
    routeTo({
      '/api/rosters': { status: 200, body: [] },
      '/api/shifts': { status: 200, body: [] },
      '/api/employees': { status: 200, body: [] },
    });
    renderPage();
    routeTo({
      '/api/rosters': { status: 403, body: { detail: 'Your role does not permit this.' } },
      '/api/shifts': { status: 200, body: [] },
      '/api/employees': { status: 200, body: [] },
    });

    await userEvent.click(await screen.findByRole('button', { name: /Generate a draft/ }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Your role may not do this'),
    );
  });
});
