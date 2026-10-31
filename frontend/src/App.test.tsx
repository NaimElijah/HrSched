import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
 * of five states it lands in decides whether somebody sees a sign-in form, a forced password
 * change, the application, or a restore that could not finish. Supplying a session directly would
 * skip exactly the part worth testing.
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
  const stub = vi.fn((path: string) =>
    respond(
      path === '/api/auth/refresh'
        ? session === null
          ? { status: 401, body: { detail: 'A valid access token is required.' } }
          : {
              status: 200,
              body: { accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 600, session },
            }
        : path === '/api/auth/session'
          ? { status: 200, body: session }
          : rest,
    ),
  );
  vi.stubGlobal('fetch', stub);
  return stub;
}

function respond(answer: Answer) {
  return Promise.resolve({
    ok: answer.status < 400,
    status: answer.status,
    headers: new Headers(),
    text: () => Promise.resolve(answer.body === null ? '' : JSON.stringify(answer.body)),
  });
}

/** How `fetch` fails when the network is down: it rejects rather than answering. */
const UNREACHABLE = 'unreachable';

/**
 * Answers as {@link loadingAs} does, except for the paths named.
 *
 * @param session who the refresh cookie belongs to
 * @param overrides read on every request, so a test can bring the network back mid-way
 */
function loadingThrough(
  session: Session,
  overrides: () => Readonly<Record<string, Answer | typeof UNREACHABLE>>,
) {
  const answering = loadingAs(session);
  const stub = vi.fn((path: string) => {
    const override = overrides()[path];
    if (override === UNREACHABLE) {
      return Promise.reject(new TypeError('Failed to fetch'));
    }
    return override === undefined ? answering(path) : respond(override);
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

/**
 * The text a screen reader reads from `element`: everything but what is hidden from it, with each
 * run of white space read as one.
 */
function spokenText(element: Element): string {
  const read = (node: Node): string =>
    node instanceof Element
      ? node.getAttribute('aria-hidden') === 'true'
        ? ''
        : Array.from(node.childNodes, read).join('')
      : (node.textContent ?? '');
  return read(element).replace(/\s+/g, ' ');
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

/**
 * The shell is drawn from the read-back, never from the copy of the session the refresh answers
 * with; `SessionProvider.tsx` says why the two can differ.
 */
describe('what a restore believes', () => {
  it('GivenARefreshWhoseSessionDiffersFromTheReadBack_WhenRestoring_ThenTheReadBackWins', async () => {
    loadingThrough(A_TENANT, () => ({
      '/api/auth/session': { status: 200, body: { ...A_TENANT, passwordChangeRequired: true } },
    }));

    show();

    expect(
      await screen.findByRole('heading', { name: 'Change your password' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'People' })).not.toBeInTheDocument();
  });
});

describe('a restore that cannot finish', () => {
  /**
   * A network that is down says nothing about the refresh cookie, so the sign-in form would be a
   * guess, and "Restoring…" forever is no answer at all (#139).
   */
  it('GivenTheNetworkFails_WhenRestoringTheSession_ThenTheAppSaysSoAndOffersARetry', async () => {
    loadingThrough(A_TENANT, () => ({ '/api/auth/refresh': UNREACHABLE }));

    show();

    expect(
      await screen.findByRole('heading', { name: 'Your session could not be restored' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Failed to fetch');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument();
  });

  /** The dev proxy, and any gateway in front of a backend that is down, answers rather than fails. */
  it('GivenTheBackendIsDownBehindAProxy_WhenRestoringTheSession_ThenTheAppSaysSoRatherThanSigningOut', async () => {
    loadingThrough(A_TENANT, () => ({ '/api/auth/refresh': { status: 502, body: null } }));

    show();

    expect(
      await screen.findByRole('heading', { name: 'Your session could not be restored' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('The server returned 502.');
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument();
  });

  it('GivenARetry_WhenTheNetworkIsBack_ThenTheSessionIsRestored', async () => {
    let down = true;
    loadingThrough(A_TENANT, () => (down ? { '/api/auth/refresh': UNREACHABLE } : {}));
    show();
    await screen.findByRole('button', { name: 'Try again' });

    down = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('button', { name: 'People' })).toBeInTheDocument();
  });

  /** While a retry is in flight nothing is known yet, so the sign-in form would be a guess too. */
  it('GivenARetryInFlight_WhenItHasNotAnsweredYet_ThenTheAppSaysItIsRestoring', async () => {
    let attempts = 0;
    let answerTheRetry: (answered: unknown) => void = () => undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(() => {
        attempts += 1;
        return attempts === 1
          ? Promise.reject(new TypeError('Failed to fetch'))
          : new Promise((resolve) => {
              answerTheRetry = resolve;
            });
      }),
    );
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Try again' }));

    try {
      expect(screen.getByRole('status')).toHaveTextContent('Restoring your session…');
      expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument();
    } finally {
      // The client shares one refresh between every caller, so a retry left unanswered here would
      // keep every later test waiting on it.
      answerTheRetry(await respond({ status: 401, body: {} }));
      await screen.findByRole('heading', { name: 'Sign in' });
    }
  });

  /**
   * The refresh worked, so the client holds a live token. The sign-in form would ask somebody who
   * is signed in to sign in again, and tell them nothing about why.
   */
  it('GivenAGoodRefresh_WhenReadingTheSessionFails_ThenTheAppDoesNotClaimTheUserSignedOut', async () => {
    loadingThrough(A_TENANT, () => ({
      '/api/auth/session': { status: 500, body: { detail: 'The database is on fire.' } },
    }));

    show();

    expect(
      await screen.findByRole('heading', { name: 'Your session could not be restored' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('The database is on fire.');
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument();
  });

  it('GivenAGoodRefresh_WhenTheNetworkFailsReadingTheSession_ThenTheAppDoesNotClaimTheUserSignedOut', async () => {
    loadingThrough(A_TENANT, () => ({ '/api/auth/session': UNREACHABLE }));

    show();

    expect(
      await screen.findByRole('heading', { name: 'Your session could not be restored' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument();
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

  /**
   * Account administration is a tenant Admin's, and the operator is refused everything inside an
   * organisation (ADR-030), so the tab would offer something that could not happen.
   */
  it('GivenTheOperator_WhenSignedIn_ThenThereIsNoAccountsTab', async () => {
    loadingAs(THE_OPERATOR);

    show();

    await screen.findByRole('button', { name: 'Organisations' });
    expect(screen.queryByRole('button', { name: 'Accounts' })).not.toBeInTheDocument();
  });

  /** The least role there is, so that tabs chosen by role rather than kind would show here. */
  it('GivenATenantInTheEmployeeRole_WhenSignedIn_ThenTheTenantTabsAreShown', async () => {
    loadingAs({ ...A_TENANT, role: 'EMPLOYEE', employee: 'emp-1' });

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
  /**
   * The shell's own failures, which are the only ones these tests are about.
   *
   * The screen inside `main` asks the backend the same question the shell does and shows its own
   * refusal, so "is this message on the page?" cannot tell the two apart — and asserting it was
   * absent only held until that screen's request came back, which made the test a race it lost on
   * CI rather than a statement about the shell.
   */
  function shellAlerts() {
    const main = screen.getByRole('main');
    return screen.queryAllByRole('alert').filter((alert) => !main.contains(alert));
  }

  it('GivenTheEmployeeRoleAndA403_WhenRendered_ThenNoFailureIsShownForThePickerList', async () => {
    loadingAs(
      { ...A_TENANT, role: 'EMPLOYEE' },
      { status: 403, body: { detail: 'Your role does not permit this.' } },
    );

    show();

    // Waiting for the screen's own refusal is what makes the assertion below deterministic: by the
    // time it is there, every request this render makes has come back.
    await within(await screen.findByRole('main')).findByRole('alert');
    expect(shellAlerts()).toHaveLength(0);
  });

  it('GivenAServerFailureOnTheEmployeeList_WhenRendered_ThenItIsSurfacedRatherThanSwallowed', async () => {
    loadingAs(A_TENANT, { status: 500, body: { detail: 'The database is on fire.' } });

    show();

    await waitFor(() => expect(shellAlerts()).toHaveLength(1));
    expect(shellAlerts()[0]).toHaveTextContent('The database is on fire.');
    expect(screen.getByRole('banner'), 'the banner').toContainElement(shellAlerts()[0] ?? null);
  });

  it('GivenANotFoundOnTheEmployeeList_WhenRendered_ThenItIsSurfacedRatherThanSwallowed', async () => {
    loadingAs(A_TENANT, { status: 404, body: { detail: 'No such resource.' } });

    show();

    await waitFor(() => expect(shellAlerts()).toHaveLength(1));
    await waitFor(() =>
      expect(shellAlerts()[0]).toHaveTextContent('Nothing here for this organisation.'),
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

  /**
   * ADR-046: a generation runs for minutes, and the Scheduler looks at other tabs meanwhile. Coming
   * back finds it still awaited, then shows how it ended, rather than a page that has forgotten it
   * and offers to start a second solve.
   */
  it('GivenAGenerationRunning_WhenTheRostersTabIsLeftAndOpenedAgain_ThenItIsStillAwaited', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const pending = { id: 'gen-1', status: 'PENDING', roster: null, violations: [] };
      let generation: Answer = { status: 200, body: pending };
      loadingThrough({ ...A_TENANT, role: 'SCHEDULER' }, () => ({
        '/api/rosters/generations': { status: 202, body: pending },
        '/api/rosters/generations/gen-1': generation,
      }));
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      show();
      await user.click(await screen.findByRole('button', { name: 'Rosters' }));
      await user.click(
        await screen.findByRole('button', { name: 'Generate a draft for this span' }),
      );
      expect(await screen.findByText('Generating a draft…')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Shifts' }));
      generation = {
        status: 200,
        body: {
          ...pending,
          status: 'REFUSED',
          violations: [
            {
              employee: 'emp-1',
              constraint: 'declared unavailability',
              detail: 'shift shift-1 runs through time they declared they cannot work',
            },
          ],
        },
      };
      await user.click(screen.getByRole('button', { name: 'Rosters' }));

      expect(await screen.findByText('Generating a draft…')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Generate a draft for this span' })).toBeDisabled();
      await vi.advanceTimersByTimeAsync(1000);
      expect(await screen.findByText(/^Generation refused/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Generate a draft for this span' })).toBeEnabled();
    } finally {
      vi.useRealTimers();
    }
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

/**
 * A tab change replaces everything inside `main`, and the tab that was pressed stays where it was,
 * so a screen-reader user hears nothing of what appeared unless focus moves to it (#257, C4). The
 * title names the view too, because it is what a browser's tab list and a screen reader's window
 * list read out.
 */
describe('what a tab change says', () => {
  beforeEach(() => {
    document.title = '';
  });

  it.each([
    ['Accounts', 'Accounts'],
    ['Qualifications', 'Qualifications'],
    ['Rules', 'Working-time rules'],
    ['Shifts', 'Shifts'],
    ['Rosters', 'Rosters'],
    ['Swaps', 'Swaps'],
    ['Me', 'Me'],
    ['Password', 'Change your password'],
  ])('GivenTheShell_WhenThe %s TabIsPressed_ThenFocusIsOnItsHeading', async (tab, heading) => {
    loadingAs(A_TENANT);
    show();

    await userEvent.click(await screen.findByRole('button', { name: tab }));

    const appeared = await screen.findByRole('heading', { name: heading });
    await waitFor(() => expect(appeared, `focus on the "${heading}" heading`).toHaveFocus());
  });

  it.each(['Accounts', 'Qualifications', 'Rules', 'Shifts', 'Rosters', 'Swaps', 'Me', 'Password'])(
    'GivenTheShell_WhenThe %s TabIsPressed_ThenTheDocumentTitleNamesIt',
    async (tab) => {
      loadingAs(A_TENANT);
      show();

      await userEvent.click(await screen.findByRole('button', { name: tab }));

      await waitFor(() => expect(document.title).toBe(`${tab} · HrSched`));
    },
  );
});

/**
 * The Operator's tabs are views as the tenant's are: each focuses its heading and names itself in
 * the title, and so does the way back to the register (#257, C4).
 */
describe("what the Operator's tab change says", () => {
  beforeEach(() => {
    document.title = '';
  });

  it('GivenTheOperator_WhenThePasswordTabIsPressed_ThenFocusIsOnItsHeadingAndTheTitleNamesIt', async () => {
    loadingAs(THE_OPERATOR);
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Password' }));

    const heading = await screen.findByRole('heading', { name: 'Change your password' });
    await waitFor(() =>
      expect(heading, 'focus on the "Change your password" heading').toHaveFocus(),
    );
    expect(document.title).toBe('Password · HrSched');
  });

  it('GivenTheOperatorOnPassword_WhenTheOrganisationsTabIsPressed_ThenFocusIsOnItsHeadingAndTheTitleNamesIt', async () => {
    loadingAs(THE_OPERATOR);
    show();
    await userEvent.click(await screen.findByRole('button', { name: 'Password' }));
    await screen.findByRole('heading', { name: 'Change your password' });

    await userEvent.click(screen.getByRole('button', { name: 'Organisations' }));

    const heading = await screen.findByRole('heading', { name: 'Organisations' });
    await waitFor(() => expect(heading, 'focus on the "Organisations" heading').toHaveFocus());
    expect(document.title).toBe('Organisations · HrSched');
  });
});

/** The channel tabs tell each other how a session ended on (ADR-048). */
const SESSION_CHANNEL = 'hrsched.session';

/**
 * Every screen outside the shell names itself in the title as it does on the page, so a browser's
 * tab list and a screen reader's window list say where somebody is (#257, C4).
 */
describe('what each screen outside the shell is titled', () => {
  beforeEach(() => {
    document.title = '';
  });

  it('GivenARestoreInFlight_WhenItHasNotAnsweredYet_ThenTheTitleSaysItIsRestoring', async () => {
    let answerTheRefresh: (answered: unknown) => void = () => undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise((resolve) => {
            answerTheRefresh = resolve;
          }),
      ),
    );
    show();

    try {
      await waitFor(() => expect(document.title).toBe('Restoring your session · HrSched'));
    } finally {
      // The client shares one refresh between every caller, so one left unanswered here would keep
      // every later test waiting on it.
      answerTheRefresh(await respond({ status: 401, body: {} }));
      await screen.findByRole('heading', { name: 'Sign in' });
    }
  });

  it('GivenARestoreThatCannotFinish_WhenItsScreenIsShown_ThenTheTitleNamesIt', async () => {
    loadingThrough(A_TENANT, () => ({ '/api/auth/refresh': UNREACHABLE }));

    show();

    await screen.findByRole('button', { name: 'Try again' });
    await waitFor(() =>
      expect(document.title).toBe('Your session could not be restored · HrSched'),
    );
  });

  it('GivenNoSession_WhenTheSignInPageIsShown_ThenTheTitleSaysSignIn', async () => {
    loadingAs(null);

    show();

    await screen.findByRole('heading', { name: 'Sign in' });
    await waitFor(() => expect(document.title).toBe('Sign in · HrSched'));
  });

  it('GivenAPasswordChangeRequired_WhenItsScreenIsShown_ThenTheTitleNamesIt', async () => {
    loadingAs({ ...A_TENANT, passwordChangeRequired: true });

    show();

    await screen.findByRole('heading', { name: 'Change your password' });
    await waitFor(() => expect(document.title).toBe('Change your password · HrSched'));
  });

  it('GivenASignOutTheBackendDidNotConfirm_WhenItsScreenIsShown_ThenTheTitleNamesIt', async () => {
    loadingThrough(A_TENANT, () => ({
      '/api/auth/sign-out': { status: 500, body: { detail: 'An invented outage.' } },
    }));
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(document.title).toBe('Sign-out not confirmed · HrSched'));
  });

  it('GivenASignedInUser_WhenSigningOut_ThenTheTitleSaysSignIn', async () => {
    loadingThrough(A_TENANT, () => ({ '/api/auth/sign-out': { status: 204, body: null } }));
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));

    await screen.findByRole('heading', { name: 'Sign in' });
    await waitFor(() => expect(document.title).toBe('Sign in · HrSched'));
  });

  it('GivenASignedInTab_WhenAnotherTabEndsTheSession_ThenTheTitleSaysSignIn', async () => {
    loadingAs(A_TENANT);
    show();
    await screen.findByRole('button', { name: 'Sign out' });
    const anotherTab = new BroadcastChannel(SESSION_CHANNEL);

    try {
      anotherTab.postMessage('ended');

      await screen.findByRole('heading', { name: 'Sign in' });
      await waitFor(() => expect(document.title).toBe('Sign in · HrSched'));
    } finally {
      anotherTab.close();
    }
  });
});

/**
 * Landmarks are how a screen-reader user jumps past what repeats to what this screen is for, so
 * every screen has a `main`, and what identifies the session sits in the `banner` (#257).
 */
describe('the landmarks', () => {
  it('GivenNoSession_WhenTheSignInPageIsShown_ThenItIsInTheMainLandmark', async () => {
    loadingAs(null);

    show();

    const heading = await screen.findByRole('heading', { name: 'Sign in' });
    expect(screen.getByRole('main'), 'the main landmark').toContainElement(heading);
  });

  it('GivenAPasswordChangeRequired_WhenItsScreenIsShown_ThenItIsInTheMainLandmark', async () => {
    loadingAs({ ...A_TENANT, passwordChangeRequired: true });

    show();

    const heading = await screen.findByRole('heading', { name: 'Change your password' });
    expect(screen.getByRole('main'), 'the main landmark').toContainElement(heading);
  });

  it('GivenARestoreThatFailed_WhenItsScreenIsShown_ThenItIsInTheMainLandmark', async () => {
    loadingThrough(A_TENANT, () => ({ '/api/auth/refresh': UNREACHABLE }));

    show();

    const heading = await screen.findByRole('heading', {
      name: 'Your session could not be restored',
    });
    const main = screen.getByRole('main');
    expect(main, 'the main landmark').toContainElement(heading);
    expect(main).toContainElement(screen.getByRole('button', { name: 'Try again' }));
  });

  it('GivenTheShell_WhenShown_ThenWhoIsSignedInAndSignOutAreInTheBanner', async () => {
    loadingAs(A_TENANT);

    show();

    await screen.findByRole('button', { name: 'Sign out' });
    const banner = screen.getByRole('banner');
    expect(within(banner).getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(banner).toHaveTextContent(`Signed in as ${A_TENANT.signInName}`);
  });

  /**
   * Shown apart by a separator, but a screen reader reads a middle dot aloud, so it is hidden from
   * one, and the two are read apart by the space around it.
   */
  it('GivenTheShell_WhenTheBannerIsRead_ThenTheSignInNameAndTheRoleAreReadApartWithNoSeparator', async () => {
    loadingAs(A_TENANT);

    show();

    await screen.findByRole('button', { name: 'Sign out' });
    const read = spokenText(screen.getByRole('banner'));
    expect(read).toContain(`${A_TENANT.signInName} ${String(A_TENANT.role)} in`);
    expect(read).not.toContain('·');
  });

  it('GivenTheShell_WhenShown_ThenThePlanningSpanIsInTheMainLandmark', async () => {
    loadingAs(A_TENANT);

    show();

    await screen.findByRole('group', { name: 'Planning span' });
    expect(within(screen.getByRole('main')).getByLabelText('Span from')).toBeInTheDocument();
  });
});

/**
 * Pressing "Try again" removes the button that had focus. Whatever the retry ends in, a sign-in
 * page, the shell, or the same failure drawn again, it replaces the screen, and unless focus moves
 * to it a screen-reader user is left on nothing (#257, C4). A first load with no session still
 * takes no focus; that rule is `signInNotice.test.tsx`'s.
 */
describe('a retried restore', () => {
  it('GivenARestoreThatFailed_WhenTheRetryFindsNoSession_ThenFocusIsOnTheSignInHeading', async () => {
    let down = true;
    loadingThrough(A_TENANT, () =>
      down
        ? { '/api/auth/refresh': UNREACHABLE }
        : { '/api/auth/refresh': { status: 401, body: { detail: 'No session.' } } },
    );
    show();
    await screen.findByRole('button', { name: 'Try again' });

    down = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    const heading = await screen.findByRole('heading', { name: 'Sign in' });
    await waitFor(() => expect(heading, 'focus on the "Sign in" heading').toHaveFocus());
  });

  it('GivenARestoreThatFailed_WhenTheRetryFailsAgain_ThenFocusIsOnItsHeading', async () => {
    loadingThrough(A_TENANT, () => ({ '/api/auth/refresh': UNREACHABLE }));
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Try again' }));

    const heading = await screen.findByRole('heading', {
      name: 'Your session could not be restored',
    });
    await waitFor(() =>
      expect(heading, 'focus on the "Your session could not be restored" heading').toHaveFocus(),
    );
  });

  it('GivenARestoreThatFailed_WhenTheRetryFindsASession_ThenFocusIsOnTheLandingViewsHeading', async () => {
    let down = true;
    loadingThrough(A_TENANT, () => (down ? { '/api/auth/refresh': UNREACHABLE } : {}));
    show();
    await screen.findByRole('button', { name: 'Try again' });

    down = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    const heading = await screen.findByRole('heading', { name: 'People' });
    await waitFor(() => expect(heading, 'focus on the "People" heading').toHaveFocus());
  });
});
