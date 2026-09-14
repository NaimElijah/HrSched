import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { ActingAsProvider } from './identity/ActingAs';

/**
 * The shell. Only what nothing else covers is tested here.
 *
 * The interesting part is the single deliberate swallow in the application. The acting-as bar needs
 * the employee list to populate its picker, and an EMPLOYEE is not permitted to read it — that one
 * refusal is expected and answered with an empty list. Every other failure has to surface, because
 * a silently empty picker during an outage is indistinguishable from an organisation with no staff.
 * Both halves of that promise are asserted; only the comment stated them before.
 */

function respondWith(outcome: { ok: boolean; status: number; body: unknown }) {
  const stub = vi.fn(() =>
    Promise.resolve({
      ok: outcome.ok,
      status: outcome.status,
      headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify(outcome.body)),
    }),
  );
  vi.stubGlobal('fetch', stub);
  return stub;
}

function actingAs(role: string) {
  window.localStorage.setItem(
    'hrsched.acting-as',
    JSON.stringify({
      organisation: '22222222-2222-4222-8222-222222222222',
      user: '33333333-3333-4333-8333-333333333333',
      role,
      employee: null,
    }),
  );
}

function show() {
  render(
    <ActingAsProvider>
      <App />
    </ActingAsProvider>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('the one deliberate swallow', () => {
  /**
   * Only the shell's own loader swallows the 403. PeoplePage runs its own and surfaces it, which is
   * right: an empty picker is a cosmetic degradation, an empty roster of colleagues is a claim.
   */
  it('GivenTheEmployeeRoleAndA403_WhenRendered_ThenThePickerStillRendersWithNobodyInIt', async () => {
    actingAs('EMPLOYEE');
    respondWith({ ok: false, status: 403, body: { detail: 'Your role does not permit this.' } });

    show();

    await waitFor(() => expect(screen.getByLabelText('Acting as employee')).toBeInTheDocument());
    expect(screen.getByLabelText('Acting as employee')).toHaveValue('');
    expect(within(screen.getByLabelText('Acting as employee')).getAllByRole('option')).toHaveLength(
      1,
    );
  });

  it('GivenAServerFailureOnTheEmployeeList_WhenRendered_ThenItIsSurfacedRatherThanSwallowed', async () => {
    actingAs('ADMIN');
    respondWith({ ok: false, status: 500, body: { detail: 'The database is on fire.' } });

    show();

    await waitFor(() =>
      expect(screen.getAllByRole('alert')[0]).toHaveTextContent('The database is on fire.'),
    );
  });

  it('GivenANotFoundOnTheEmployeeList_WhenRendered_ThenItIsSurfacedRatherThanSwallowed', async () => {
    actingAs('ADMIN');
    respondWith({ ok: false, status: 404, body: { detail: 'No such resource.' } });

    show();

    await waitFor(() =>
      expect(screen.getAllByRole('alert')[0]).toHaveTextContent(
        'Nothing here for this organisation.',
      ),
    );
  });
});

describe('navigating', () => {
  it('GivenTheApplication_WhenOpened_ThenPeopleIsTheCurrentTab', async () => {
    actingAs('ADMIN');
    respondWith({ ok: true, status: 200, body: [] });

    show();

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'People' })).toHaveAttribute(
        'aria-current',
        'page',
      ),
    );
  });

  it('GivenATabClicked_WhenPressed_ThenThatScreenIsShownAndMarkedCurrent', async () => {
    actingAs('ADMIN');
    respondWith({ ok: true, status: 200, body: [] });
    show();

    await userEvent.click(screen.getByRole('button', { name: 'Qualifications' }));

    expect(screen.getByRole('button', { name: 'Qualifications' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByRole('heading', { name: 'Qualifications' })).toBeInTheDocument();
  });

  it('GivenTheSwapsTab_WhenPressed_ThenTheDecisionQueueIsShown', async () => {
    actingAs('SCHEDULER');
    respondWith({ ok: true, status: 200, body: [] });
    show();

    await userEvent.click(screen.getByRole('button', { name: 'Swaps' }));

    expect(screen.getByRole('heading', { name: 'Swaps' })).toBeInTheDocument();
  });

  it('GivenTheOrganisationsTab_WhenPressed_ThenThePlatformScreenIsShown', async () => {
    actingAs('OPERATOR');
    respondWith({ ok: true, status: 200, body: [] });
    show();

    await userEvent.click(screen.getByRole('button', { name: 'Organisations' }));

    expect(screen.getByRole('heading', { name: 'Organisations' })).toBeInTheDocument();
  });

  /** Both dates used to sit inside one label, so a screen reader named them identically. */
  it('GivenThePlanningSpan_WhenRendered_ThenEachDateHasItsOwnName', async () => {
    actingAs('ADMIN');
    respondWith({ ok: true, status: 200, body: [] });

    show();

    const span = await screen.findByRole('group', { name: 'Planning span' });
    expect(within(span).getByLabelText('Span from')).toHaveAttribute('type', 'date');
    expect(within(span).getByLabelText('Span to')).toHaveAttribute('type', 'date');
  });

  it('GivenTheMeTab_WhenOpenedWithoutAnEmployeeLink_ThenItExplainsWhatToDo', async () => {
    actingAs('EMPLOYEE');
    respondWith({ ok: false, status: 403, body: { detail: 'Your role does not permit this.' } });
    show();

    await userEvent.click(screen.getByRole('button', { name: 'Me' }));

    expect(screen.getByText(/different identities on purpose/i)).toBeInTheDocument();
  });
});
