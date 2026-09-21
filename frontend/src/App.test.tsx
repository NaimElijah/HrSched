import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import type { Session } from './api/session';
import { endSession } from './api/session';
import { SessionProvider } from './auth/SessionProvider';
import { A_TENANT, THE_OPERATOR } from './test-support/session';

/**
 * The shell, driven through the real {@link SessionProvider} rather than a stubbed session.
 *
 * That is deliberate: what this file is mostly about is the handshake every load begins with. The
 * app asks the backend whether the refresh cookie is still good before it draws anything, and which
 * of four states it lands in decides whether somebody sees a sign-in form, a forced password
 * change, or the application. Supplying a session directly would skip exactly the part worth
 * testing.
 *
 * It also covers the one deliberate swallow in the application. The shell needs the employee list
 * for its pickers, and an EMPLOYEE is not permitted to read it — that one refusal is expected and
 * answered with an empty list. Every other failure has to surface, because a silently empty picker
 * during an outage is indistinguishable from an organisation with no staff.
 */

interface Answer {
  readonly status: number;
  readonly body: unknown;
}

/**
 * Answers the two endpoints every load begins with, and whatever else the test names.
 *
 * @param session who the refresh cookie turns out to belong to, or null for nobody
 * @param rest what any other path answers with
 */
function loadingAs(session: Session | null, rest: Answer = { status: 200, body: [] }) {
  const stub = vi.fn((path: string) => {
    const answer: Answer =
      path === '/api/auth/refresh'
        ? session === null
          ? { status: 401, body: { detail: 'A valid access token is required.' } }
          : {
              status: 200,
              body: { accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 600, session },
            }
        : path === '/api/auth/session'
          ? { status: 200, body: session }
          : rest;
    return Promise.resolve({
      ok: answer.status < 400,
      status: answer.status,
      headers: new Headers(),
      text: () => Promise.resolve(answer.body === null ? '' : JSON.stringify(answer.body)),
    });
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

function show() {
  render(
    <SessionProvider>
      <App />
    </SessionProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  endSession();
});

describe('what a load begins with', () => {
  it('GivenNoSession_WhenTheAppLoads_ThenTheSignInPageIsShown', async () => {
    loadingAs(null);

    show();

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'People' })).not.toBeInTheDocument();
  });

  /** A reload keeps somebody signed in: the access token is gone, the refresh cookie is not. */
  it('GivenAReload_WhenTheRefreshCookieIsValid_ThenTheSessionIsRestored', async () => {
    const stub = loadingAs(A_TENANT);

    show();

    expect(await screen.findByRole('button', { name: 'People' })).toBeInTheDocument();
    expect(stub.mock.calls.some(([path]) => path === '/api/auth/refresh')).toBe(true);
    expect(screen.getByText('ada@example.com')).toBeInTheDocument();
  });

  it('GivenAPasswordChangeRequired_WhenSignedIn_ThenOnlyTheChangePasswordScreenIsShown', async () => {
    loadingAs({ ...A_TENANT, passwordChangeRequired: true });

    show();

    expect(
      await screen.findByRole('heading', { name: 'Change your password' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'People' })).not.toBeInTheDocument();
    expect(screen.getByText(/set by somebody else/i)).toBeInTheDocument();
  });
});

describe('what each kind of account sees', () => {
  /**
   * Tabs follow the kind of account, never the role. An operator belongs to no organisation, so
   * every tenant screen would have nothing to show and every request would be refused (ADR-030).
   */
  it('GivenTheOperator_WhenSignedIn_ThenOnlyTheOrganisationsTabIsShown', async () => {
    loadingAs(THE_OPERATOR);

    show();

    expect(await screen.findByRole('button', { name: 'Organisations' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'People' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Rosters' })).not.toBeInTheDocument();
  });

  it('GivenATenant_WhenSignedIn_ThenTheTenantTabsAreShown', async () => {
    loadingAs(A_TENANT);

    show();

    expect(await screen.findByRole('button', { name: 'People' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Rosters' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Organisations' })).not.toBeInTheDocument();
  });

  it('GivenTheOperator_WhenSignedIn_ThenThePlatformScreenIsShown', async () => {
    loadingAs(THE_OPERATOR);

    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Organisations' }));

    expect(screen.getByRole('heading', { name: 'Organisations' })).toBeInTheDocument();
  });
});

describe('the one deliberate swallow', () => {
  it('GivenTheEmployeeRoleAndA403_WhenRendered_ThenNoFailureIsShownForThePickerList', async () => {
    loadingAs(
      { ...A_TENANT, role: 'EMPLOYEE' },
      { status: 403, body: { detail: 'Your role does not permit this.' } },
    );

    show();

    await screen.findByRole('button', { name: 'People' });
    await waitFor(() =>
      expect(screen.queryByText('Your role does not permit this.')).not.toBeInTheDocument(),
    );
  });

  it('GivenAServerFailureOnTheEmployeeList_WhenRendered_ThenItIsSurfacedRatherThanSwallowed', async () => {
    loadingAs(A_TENANT, { status: 500, body: { detail: 'The database is on fire.' } });

    show();

    await waitFor(() =>
      expect(screen.getAllByRole('alert')[0]).toHaveTextContent('The database is on fire.'),
    );
  });

  it('GivenANotFoundOnTheEmployeeList_WhenRendered_ThenItIsSurfacedRatherThanSwallowed', async () => {
    loadingAs(A_TENANT, { status: 404, body: { detail: 'No such resource.' } });

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
    loadingAs(A_TENANT);

    show();

    expect(await screen.findByRole('button', { name: 'People' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('GivenATabClicked_WhenPressed_ThenThatScreenIsShownAndMarkedCurrent', async () => {
    loadingAs(A_TENANT);
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Qualifications' }));

    expect(screen.getByRole('button', { name: 'Qualifications' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByRole('heading', { name: 'Qualifications' })).toBeInTheDocument();
  });

  it('GivenTheSwapsTab_WhenPressed_ThenTheDecisionQueueIsShown', async () => {
    loadingAs({ ...A_TENANT, role: 'SCHEDULER' });
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Swaps' }));

    expect(screen.getByRole('heading', { name: 'Swaps' })).toBeInTheDocument();
  });

  /** Both dates used to sit inside one label, so a screen reader named them identically. */
  it('GivenThePlanningSpan_WhenRendered_ThenEachDateHasItsOwnName', async () => {
    loadingAs(A_TENANT);

    show();

    const span = await screen.findByRole('group', { name: 'Planning span' });
    expect(within(span).getByLabelText('Span from')).toHaveAttribute('type', 'date');
    expect(within(span).getByLabelText('Span to')).toHaveAttribute('type', 'date');
  });

  it('GivenTheMeTab_WhenOpenedWithoutAnEmployeeLink_ThenItExplainsWhatToDo', async () => {
    loadingAs(
      { ...A_TENANT, role: 'EMPLOYEE' },
      { status: 403, body: { detail: 'Your role does not permit this.' } },
    );
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Me' }));

    expect(screen.getByText(/different identities on purpose/i)).toBeInTheDocument();
  });

  it('GivenThePasswordTab_WhenPressed_ThenTheChangeScreenIsShown', async () => {
    loadingAs(A_TENANT);
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Password' }));

    expect(screen.getByRole('heading', { name: 'Change your password' })).toBeInTheDocument();
  });
});
