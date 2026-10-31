import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { currentAccessToken, endSession, holdSession } from '../api/session';
import type { Session } from '../api/session';
import { SessionProvider } from './SessionProvider';
import { defaultSpan, localDate } from '../components/localDates';
import { callsTo, holdingWrites, routeTo } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import { A_TENANT, THE_OPERATOR } from '../test-support/session';
import { WITHOUT_PAUSES } from '../test-support/typing';

/**
 * Signing in, signing out, and what happens when a session ends underneath somebody.
 *
 * Driven through the whole app rather than through the forms alone, because the property worth
 * asserting is what is on screen afterwards: a refused sign-in must leave the form saying one thing
 * whatever went wrong, and a session that ends mid-use must put the sign-in page back rather than
 * leaving a shell whose every request is refused.
 *
 * Every password here is invented for the test.
 */

const PASSWORD = 'an invented password for a test';

/** What the shell reads once somebody is signed in, answered empty unless a test says otherwise. */
const THE_SHELL: Readonly<Record<string, Answer>> = {
  '/api/employees': { status: 200, body: [] },
  '/api/qualifications': { status: 200, body: [] },
};

/** Routes the handshake, the shell, and whatever else a test names, with `routeTo`'s strictness. */
function serving(answers: Readonly<Record<string, Answer>>) {
  return routeTo({ ...THE_SHELL, ...answers });
}

function signedInBody(session: Session = A_TENANT) {
  return { accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 600, session };
}

/**
 * The detail a refused sign-in carries in these tests: invented, and one the page must never repeat,
 * because the backend's own words could say which of the reasons it was.
 */
const ACCOUNT_LOCKED = 'Account locked.';

const NO_COOKIE: Answer = {
  status: 401,
  body: { detail: 'A valid access token is required.' },
};

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

describe('signing in', () => {
  it('GivenTheRightCredentials_WhenSigningIn_ThenTheShellIsShown', async () => {
    serving({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
    });
    show();

    await userEvent.type(
      await screen.findByLabelText('Sign-in name'),
      'ada@example.com',
      WITHOUT_PAUSES,
    );
    await userEvent.type(screen.getByLabelText('Password'), PASSWORD, WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('button', { name: 'People' })).toBeInTheDocument();
  });

  it('GivenTheTypedCredentials_WhenSigningIn_ThenExactlyThoseAreSent', async () => {
    const stub = serving({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
    });
    show();

    await userEvent.type(
      await screen.findByLabelText('Sign-in name'),
      'ada@example.com',
      WITHOUT_PAUSES,
    );
    await userEvent.type(screen.getByLabelText('Password'), PASSWORD, WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await screen.findByRole('button', { name: 'People' });
    const sent = callsTo(stub, 'POST', '/api/auth/sign-in');
    expect(sent, 'the sign-ins sent').toHaveLength(1);
    expect(JSON.parse(String(sent[0]?.[1]?.body))).toEqual({
      signInName: 'ada@example.com',
      password: PASSWORD,
    });
  });

  /**
   * The backend answers an unknown name, a wrong password, a disabled account and a locked one
   * identically, and this screen must not undo that by guessing at something friendlier.
   */
  it('GivenASignInRefused_WhenShown_ThenItDoesNotSayWhetherTheAccountExists', async () => {
    serving({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': {
        status: 401,
        body: { title: 'Sign-in refused', detail: ACCOUNT_LOCKED },
      },
    });
    show();

    await userEvent.type(
      await screen.findByLabelText('Sign-in name'),
      'nobody@example.com',
      WITHOUT_PAUSES,
    );
    await userEvent.type(screen.getByLabelText('Password'), PASSWORD, WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    const refusal = await screen.findByRole('alert');
    expect(refusal).toHaveTextContent('Those credentials were not accepted.');
    expect(refusal).not.toHaveTextContent(ACCOUNT_LOCKED);
    expect(refusal.textContent).not.toMatch(/locked|disabled|unknown|does not exist|no such/i);
  });
});

describe('signing out', () => {
  it('GivenASignedInUser_WhenSigningOut_ThenTheSignInPageIsShownAndTheTokenIsForgotten', async () => {
    serving({
      'POST /api/auth/refresh': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
      'POST /api/auth/sign-out': { status: 204, body: null },
    });
    show();
    await screen.findByRole('button', { name: 'People' });

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(currentAccessToken()).toBeNull();
  });
});

describe('changing a password', () => {
  it('GivenAChangedPassword_WhenAccepted_ThenTheShellIsShown', async () => {
    const stub = serving({
      'POST /api/auth/refresh': {
        status: 200,
        body: signedInBody({ ...A_TENANT, passwordChangeRequired: true }),
      },
      '/api/auth/session': { status: 200, body: { ...A_TENANT, passwordChangeRequired: true } },
      'POST /api/auth/password': { status: 200, body: signedInBody() },
    });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });

    await userEvent.type(screen.getByLabelText('Current password'), PASSWORD, WITHOUT_PAUSES);
    await userEvent.type(
      screen.getByLabelText('New password'),
      'another invented password',
      WITHOUT_PAUSES,
    );
    await userEvent.type(
      screen.getByLabelText('New password again'),
      'another invented password',
      WITHOUT_PAUSES,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByRole('button', { name: 'People' })).toBeInTheDocument();
    const sent = callsTo(stub, 'POST', '/api/auth/password');
    expect(sent, 'the password changes sent').toHaveLength(1);
    expect(JSON.parse(String(sent[0]?.[1]?.body))).toEqual({
      currentPassword: PASSWORD,
      newPassword: 'another invented password',
    });
  });

  /**
   * From the Password tab nothing else changes on screen when a change is accepted: the fields
   * empty, and without a word that reads the same as a change that did nothing.
   */
  it('GivenAChangeFromThePasswordTab_WhenAccepted_ThenItSaysSo', async () => {
    serving({
      'POST /api/auth/refresh': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
      'POST /api/auth/password': { status: 200, body: signedInBody() },
    });
    show();
    await userEvent.click(await screen.findByRole('button', { name: 'Password' }));
    await screen.findByRole('heading', { name: 'Change your password' });
    const form = within(screen.getByRole('main'));

    await userEvent.type(form.getByLabelText('Current password'), PASSWORD, WITHOUT_PAUSES);
    await userEvent.type(
      form.getByLabelText('New password'),
      'another invented password',
      WITHOUT_PAUSES,
    );
    await userEvent.type(
      form.getByLabelText('New password again'),
      'another invented password',
      WITHOUT_PAUSES,
    );
    await userEvent.click(form.getByRole('button', { name: 'Change password' }));

    expect(await form.findByText('Your password has been changed.')).toHaveAttribute(
      'role',
      'status',
    );
    expect(form.getByLabelText('Current password')).toHaveValue('');
  });

  it('GivenAChangeFromThePasswordTab_WhenRefused_ThenNothingSaysItWasChanged', async () => {
    serving({
      'POST /api/auth/refresh': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
      'POST /api/auth/password': {
        status: 403,
        body: { detail: 'The current password is not right.' },
      },
    });
    show();
    await userEvent.click(await screen.findByRole('button', { name: 'Password' }));
    await screen.findByRole('heading', { name: 'Change your password' });
    const form = within(screen.getByRole('main'));

    await userEvent.type(form.getByLabelText('Current password'), PASSWORD, WITHOUT_PAUSES);
    await userEvent.type(
      form.getByLabelText('New password'),
      'another invented password',
      WITHOUT_PAUSES,
    );
    await userEvent.type(
      form.getByLabelText('New password again'),
      'another invented password',
      WITHOUT_PAUSES,
    );
    await userEvent.click(form.getByRole('button', { name: 'Change password' }));

    expect(await form.findByRole('alert')).toHaveTextContent('The current password is not right.');
    expect(form.queryByText('Your password has been changed.')).not.toBeInTheDocument();
  });

  it('GivenTwoNewPasswordsThatDiffer_WhenSubmitted_ThenNothingIsSent', async () => {
    const stub = serving({
      'POST /api/auth/refresh': {
        status: 200,
        body: signedInBody({ ...A_TENANT, passwordChangeRequired: true }),
      },
      '/api/auth/session': { status: 200, body: { ...A_TENANT, passwordChangeRequired: true } },
    });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });

    await userEvent.type(screen.getByLabelText('Current password'), PASSWORD, WITHOUT_PAUSES);
    await userEvent.type(
      screen.getByLabelText('New password'),
      'one invented password',
      WITHOUT_PAUSES,
    );
    await userEvent.type(
      screen.getByLabelText('New password again'),
      'a different one',
      WITHOUT_PAUSES,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() =>
      expect(screen.getByLabelText('New password again')).toHaveAccessibleDescription(
        expect.stringContaining('do not match'),
      ),
    );
    expect(stub.mock.calls.filter(([path]) => path === '/api/auth/password')).toHaveLength(0);
  });

  /** The rule is the backend's, and it says so in its own words rather than being repeated here. */
  it('GivenANewPasswordTheBackendRefuses_WhenShown_ThenItsOwnReasonIsDisplayed', async () => {
    serving({
      'POST /api/auth/refresh': {
        status: 200,
        body: signedInBody({ ...A_TENANT, passwordChangeRequired: true }),
      },
      '/api/auth/session': { status: 200, body: { ...A_TENANT, passwordChangeRequired: true } },
      'POST /api/auth/password': {
        status: 422,
        body: { detail: 'A password must be at least 15 characters long.', reason: 'TOO_SHORT' },
      },
    });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });

    await userEvent.type(screen.getByLabelText('Current password'), PASSWORD, WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('New password'), 'short', WITHOUT_PAUSES);
    await userEvent.type(screen.getByLabelText('New password again'), 'short', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'A password must be at least 15 characters long.',
    );
  });

  /** No field suggests a password, and none carries a default. */
  it('GivenTheChangeScreen_WhenShown_ThenNoPasswordFieldCarriesAValue', async () => {
    serving({
      'POST /api/auth/refresh': {
        status: 200,
        body: signedInBody({ ...A_TENANT, passwordChangeRequired: true }),
      },
      '/api/auth/session': { status: 200, body: { ...A_TENANT, passwordChangeRequired: true } },
    });
    show();

    await screen.findByRole('heading', { name: 'Change your password' });

    expect(screen.getByLabelText('New password')).toHaveValue('');
    expect(screen.getByLabelText('New password again')).toHaveValue('');
  });
});

describe('a session that ends underneath somebody', () => {
  it('GivenTheSessionEnds_WhenARequestIsRefused_ThenTheSignInPageIsShown', async () => {
    const server: Record<string, Answer> = {
      ...THE_SHELL,
      'POST /api/auth/refresh': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
    };
    routeTo(server);
    show();
    await screen.findByRole('button', { name: 'People' });

    // The session ends on the server: from here its refresh and every read are refused.
    server['POST /api/auth/refresh'] = NO_COOKIE;
    server['/api/employees'] = NO_COOKIE;
    server['/api/qualifications'] = NO_COOKIE;
    await userEvent.click(screen.getByRole('button', { name: 'Qualifications' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    await waitFor(() => expect(currentAccessToken()).toBeNull());
  });
});

/**
 * A renewal answers with the session as the backend now holds it. A role or an employee link an
 * Admin changed since sign-in arrives that way, and the screens must act on it: a page still
 * acting on the old link would declare unavailability for somebody the account no longer is.
 */
describe('a session renewed underneath somebody', () => {
  const EMPLOYEE_BEFORE = '66666666-6666-4666-8666-666666666666';
  const EMPLOYEE_AFTER = '88888888-8888-4888-8888-888888888888';
  const ANOTHER_ACCOUNT = '99999999-9999-4999-8999-999999999999';

  /**
   * Restores `before`, then renews as `after` on the next read of `refused`: that read is refused
   * once, as one sent with an expired token is, and answered once the renewal has been sent.
   */
  async function renewedAs(before: Session, after: Session, refused: string) {
    const server: Record<string, Answer> = {
      ...THE_SHELL,
      'POST /api/auth/refresh': { status: 200, body: signedInBody(before) },
      '/api/auth/session': { status: 200, body: before },
    };
    const routed = routeTo(server);
    show();
    await screen.findByRole('button', { name: 'People' });
    const answered = server[refused] ?? { status: 200, body: [] };
    server[refused] = NO_COOKIE;
    server['POST /api/auth/refresh'] = { status: 200, body: signedInBody(after) };
    vi.stubGlobal(
      'fetch',
      vi.fn((path: string, init?: RequestInit) => {
        if (path === '/api/auth/refresh') {
          server[refused] = answered;
        }
        return routed(path, init);
      }),
    );
    return routed;
  }

  it('GivenARenewalCarryingAChangedRole_WhenItIsHeld_ThenTheShellShowsTheNewRole', async () => {
    await renewedAs(
      { ...A_TENANT, role: 'SCHEDULER' },
      { ...A_TENANT, role: 'ADMIN' },
      '/api/qualifications',
    );
    expect(screen.getByText(/^SCHEDULER in/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Qualifications' }));

    expect(await screen.findByText(/^ADMIN in/)).toBeInTheDocument();
  });

  /**
   * What a page loaded was loaded under the old role, the shell's list of colleagues included, and
   * a demoted account may no longer read it: a changed role reads everything on screen again.
   */
  it('GivenARenewalCarryingAChangedRole_WhenItIsHeld_ThenWhatIsOnScreenIsReadAgain', async () => {
    const routed = await renewedAs(
      { ...A_TENANT, role: 'ADMIN' },
      { ...A_TENANT, role: 'SCHEDULER' },
      '/api/qualifications',
    );
    const colleaguesRead = callsTo(routed, 'GET', '/api/employees').length;
    const qualificationsRead = callsTo(routed, 'GET', '/api/qualifications').length;

    await userEvent.click(screen.getByRole('button', { name: 'Qualifications' }));

    // Refused, retried after the renewal, and read again under the new role.
    await waitFor(() =>
      expect(callsTo(routed, 'GET', '/api/qualifications').length).toBeGreaterThanOrEqual(
        qualificationsRead + 3,
      ),
    );
    expect(callsTo(routed, 'GET', '/api/employees').length).toBeGreaterThan(colleaguesRead);
  });

  /** A password reset by an Admin since sign-in leaves the account able to do one thing only. */
  it('GivenARenewalRequiringAPasswordChange_WhenItIsHeld_ThenOnlyTheChangePasswordScreenIsShown', async () => {
    await renewedAs(A_TENANT, { ...A_TENANT, passwordChangeRequired: true }, '/api/qualifications');

    await userEvent.click(screen.getByRole('button', { name: 'Qualifications' }));

    expect(
      await screen.findByRole('heading', { name: 'Change your password' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'People' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Qualifications' })).not.toBeInTheDocument();
  });

  /**
   * The client never holds another account's session while one is on screen today: a renewal for
   * another account is refused before it is held. Were one held anyway, the client would act as
   * that account under a shell naming this one (#250), so this tab's session is forgotten instead.
   */
  it('GivenASessionOnScreen_WhenAnotherAccountsSessionIsHeld_ThenThisTabsSessionIsForgotten', async () => {
    serving({
      'POST /api/auth/refresh': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
    });
    show();
    await screen.findByRole('button', { name: 'People' });

    act(() => {
      holdSession(
        signedInBody({ ...A_TENANT, userId: ANOTHER_ACCOUNT, signInName: 'grace@example.com' }),
      );
    });

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(currentAccessToken()).toBeNull();
  });

  it('GivenARenewalCarryingAMovedEmployeeLink_WhenItIsHeld_ThenOwnRecordsAreReadForTheNewLink', async () => {
    const routed = await renewedAs(
      { ...A_TENANT, employee: EMPLOYEE_BEFORE },
      { ...A_TENANT, employee: EMPLOYEE_AFTER },
      '/api/me/schedule',
    );

    await userEvent.click(screen.getByRole('button', { name: 'Me' }));

    await waitFor(() =>
      expect(
        callsTo(routed, 'GET', `/api/employees/${EMPLOYEE_AFTER}/unavailability`).length,
      ).toBeGreaterThan(0),
    );
  });
});

/**
 * Routes as {@link serving} does, but holds one request until the returned `release` is called,
 * and records each time that request was sent. Only the one named is held: the refresh every load
 * begins with is a write too, so holding every write would hold the app in `restoring`.
 *
 * @param answers what the server answers, the held request included
 * @param held the `METHOD path` to hold
 */
function holding(answers: Readonly<Record<string, Answer>>, held: string) {
  const routed = serving(answers);
  const sent: unknown[] = [];
  let release!: () => void;
  const released = new Promise<void>((resolve) => {
    release = resolve;
  });
  vi.stubGlobal(
    'fetch',
    vi.fn((path: string, init?: RequestInit) => {
      if (`${init?.method ?? 'GET'} ${path}` !== held) {
        return routed(path, init);
      }
      sent.push(init?.body);
      return released.then(() => routed(path, init));
    }),
  );
  return { sent, release };
}

const MUST_CHANGE: Session = { ...A_TENANT, passwordChangeRequired: true };

/** An invented replacement password, typed into both new-password fields. */
const REPLACEMENT = 'another invented password';

async function signInAs(signInName: string) {
  await userEvent.type(await screen.findByLabelText('Sign-in name'), signInName, WITHOUT_PAUSES);
  await userEvent.type(screen.getByLabelText('Password'), PASSWORD, WITHOUT_PAUSES);
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

async function fillChangePassword(
  scope: Pick<typeof screen, 'getByLabelText'>,
  replacement = REPLACEMENT,
  confirmation = REPLACEMENT,
) {
  await userEvent.type(scope.getByLabelText('Current password'), PASSWORD, WITHOUT_PAUSES);
  await userEvent.type(scope.getByLabelText('New password'), replacement, WITHOUT_PAUSES);
  await userEvent.type(scope.getByLabelText('New password again'), confirmation, WITHOUT_PAUSES);
}

/**
 * Signing in and finishing the first password change each replace the whole screen, and the button
 * that was pressed goes with it, so focus moves to the heading of what appeared (#257, C4).
 */
describe('where focus goes when the session changes', () => {
  beforeEach(() => {
    document.title = '';
  });

  it('GivenTheRightCredentials_WhenSignedIn_ThenFocusIsOnTheLandingViewsHeading', async () => {
    serving({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
    });
    show();

    await signInAs(A_TENANT.signInName);

    const heading = await screen.findByRole('heading', { name: 'People' });
    await waitFor(() => expect(heading, 'focus on the "People" heading').toHaveFocus());
  });

  it('GivenTheRightCredentials_WhenSignedIn_ThenTheDocumentTitleNamesTheLandingView', async () => {
    serving({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
    });
    show();

    await signInAs(A_TENANT.signInName);

    await screen.findByRole('heading', { name: 'People' });
    await waitFor(() => expect(document.title).toBe('People · HrSched'));
  });

  it('GivenATemporaryPassword_WhenSignedIn_ThenFocusIsOnTheChangePasswordHeading', async () => {
    serving({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': { status: 200, body: signedInBody(MUST_CHANGE) },
      '/api/auth/session': { status: 200, body: MUST_CHANGE },
    });
    show();

    await signInAs(MUST_CHANGE.signInName);

    const heading = await screen.findByRole('heading', { name: 'Change your password' });
    await waitFor(() =>
      expect(heading, 'focus on the "Change your password" heading').toHaveFocus(),
    );
  });

  /**
   * The change screen goes the moment the change is accepted, its own status with it, so the shell
   * is what says the change happened.
   */
  it('GivenTheFirstPasswordChange_WhenAccepted_ThenTheShellSaysSo', async () => {
    serving({
      'POST /api/auth/refresh': { status: 200, body: signedInBody(MUST_CHANGE) },
      '/api/auth/session': { status: 200, body: MUST_CHANGE },
      'POST /api/auth/password': { status: 200, body: signedInBody() },
    });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });

    await fillChangePassword(screen);
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    await screen.findByRole('button', { name: 'People' });
    await waitFor(() =>
      expect(
        screen.queryAllByRole('status').map((status) => status.textContent),
        'what the status regions in the shell say',
      ).toContainEqual(expect.stringContaining('Your password has been changed')),
    );
  });

  it('GivenTheFirstPasswordChange_WhenAccepted_ThenFocusIsOnTheLandingViewsHeading', async () => {
    serving({
      'POST /api/auth/refresh': { status: 200, body: signedInBody(MUST_CHANGE) },
      '/api/auth/session': { status: 200, body: MUST_CHANGE },
      'POST /api/auth/password': { status: 200, body: signedInBody() },
    });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });

    await fillChangePassword(screen);
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    const heading = await screen.findByRole('heading', { name: 'People' });
    await waitFor(() => expect(heading, 'focus on the "People" heading').toHaveFocus());
  });

  it('GivenTheOperatorsCredentials_WhenSignedIn_ThenFocusIsOnTheOrganisationsHeadingAndTheTitleNamesIt', async () => {
    serving({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': { status: 200, body: signedInBody(THE_OPERATOR) },
      '/api/organisations': { status: 200, body: [] },
    });
    show();

    await signInAs(THE_OPERATOR.signInName);

    const heading = await screen.findByRole('heading', { name: 'Organisations' });
    await waitFor(() => expect(heading, 'focus on the "Organisations" heading').toHaveFocus());
    expect(document.title).toBe('Organisations · HrSched');
  });

  /** The notice is about the screen that went; the next view change is about something else. */
  it('GivenTheFirstPasswordChangeAccepted_WhenTheNextTabIsChosen_ThenTheNoticeIsCleared', async () => {
    serving({
      'POST /api/auth/refresh': { status: 200, body: signedInBody(MUST_CHANGE) },
      '/api/auth/session': { status: 200, body: MUST_CHANGE },
      'POST /api/auth/password': { status: 200, body: signedInBody() },
    });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });
    await fillChangePassword(screen);
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
    await waitFor(() =>
      expect(screen.getAllByRole('status').map((status) => status.textContent)).toContainEqual(
        expect.stringContaining('Your password has been changed'),
      ),
    );

    await userEvent.click(screen.getByRole('button', { name: 'Qualifications' }));

    await screen.findByRole('heading', { name: 'Qualifications' });
    await waitFor(() =>
      expect(
        screen.queryAllByRole('status').map((status) => status.textContent),
        'what the status regions in the shell say',
      ).not.toContainEqual(expect.stringContaining('Your password has been changed')),
    );
  });
});

/**
 * A button that is `disabled` while its request is out drops focus to the page, so a keyboard user
 * who pressed it is sent back to the top. It says it cannot act yet with `aria-disabled` instead,
 * and a second press does nothing (#257, C1). jsdom moves no focus off a disabled element, so the
 * attribute is what proves it.
 */
describe('a submit in flight', () => {
  it('GivenASignInInFlight_WhenItsButtonIsPressed_ThenItIsAriaDisabledAndKeepsFocus', async () => {
    const { release } = holding(
      {
        'POST /api/auth/refresh': NO_COOKIE,
        'POST /api/auth/sign-in': { status: 200, body: signedInBody() },
        '/api/auth/session': { status: 200, body: A_TENANT },
      },
      'POST /api/auth/sign-in',
    );
    show();
    await userEvent.type(
      await screen.findByLabelText('Sign-in name'),
      A_TENANT.signInName,
      WITHOUT_PAUSES,
    );
    await userEvent.type(screen.getByLabelText('Password'), PASSWORD, WITHOUT_PAUSES);
    const button = screen.getByRole('button', { name: 'Sign in' });

    await userEvent.click(button);

    try {
      expect(button, 'the Sign in button while it is in flight').toHaveAttribute(
        'aria-disabled',
        'true',
      );
      expect(button).not.toBeDisabled();
      expect(button).toHaveFocus();
    } finally {
      release();
      await screen.findByRole('button', { name: 'People' });
    }
  });

  it('GivenASignInInFlight_WhenItsButtonIsPressedAgain_ThenNoSecondSignInIsSent', async () => {
    const { sent, release } = holding(
      {
        'POST /api/auth/refresh': NO_COOKIE,
        'POST /api/auth/sign-in': { status: 200, body: signedInBody() },
        '/api/auth/session': { status: 200, body: A_TENANT },
      },
      'POST /api/auth/sign-in',
    );
    show();
    await signInAs(A_TENANT.signInName);
    // Named by what it starts with, because what it says while in flight is the page's to choose.
    const button = screen.getByRole('button', { name: /^Sign/ });

    try {
      expect(button, 'the Sign in button while it is in flight').toHaveAttribute(
        'aria-disabled',
        'true',
      );
      await userEvent.click(button);
      expect(sent, 'the sign-ins sent').toHaveLength(1);
    } finally {
      release();
      await screen.findByRole('button', { name: 'People' });
    }
  });

  it('GivenAPasswordChangeInFlight_WhenItsButtonIsPressed_ThenItIsAriaDisabledAndKeepsFocus', async () => {
    const { release } = holding(
      {
        'POST /api/auth/refresh': { status: 200, body: signedInBody(MUST_CHANGE) },
        '/api/auth/session': { status: 200, body: MUST_CHANGE },
        'POST /api/auth/password': { status: 200, body: signedInBody() },
      },
      'POST /api/auth/password',
    );
    show();
    await screen.findByRole('heading', { name: 'Change your password' });
    await fillChangePassword(screen);
    const button = screen.getByRole('button', { name: 'Change password' });

    await userEvent.click(button);

    try {
      expect(button, 'the Change password button while it is in flight').toHaveAttribute(
        'aria-disabled',
        'true',
      );
      expect(button).not.toBeDisabled();
      expect(button).toHaveFocus();
    } finally {
      release();
      await screen.findByRole('button', { name: 'People' });
    }
  });

  it('GivenAPasswordChangeInFlight_WhenItsButtonIsPressedAgain_ThenNoSecondChangeIsSent', async () => {
    const { sent, release } = holding(
      {
        'POST /api/auth/refresh': { status: 200, body: signedInBody(MUST_CHANGE) },
        '/api/auth/session': { status: 200, body: MUST_CHANGE },
        'POST /api/auth/password': { status: 200, body: signedInBody() },
      },
      'POST /api/auth/password',
    );
    show();
    await screen.findByRole('heading', { name: 'Change your password' });
    await fillChangePassword(screen);
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
    // Named by what it starts with, because what it says while in flight is the page's to choose.
    const button = screen.getByRole('button', { name: /^Chang/ });

    try {
      expect(button, 'the Change password button while it is in flight').toHaveAttribute(
        'aria-disabled',
        'true',
      );
      await userEvent.click(button);
      expect(sent, 'the password changes sent').toHaveLength(1);
    } finally {
      release();
      await screen.findByRole('button', { name: 'People' });
    }
  });
});

/**
 * A refusal about one field is shown at that field: it is marked invalid, it is described by the
 * message, and a screen-reader user landing on it hears why (#257, C6).
 */
describe('a password change refused at a field', () => {
  it('GivenTwoNewPasswordsThatDiffer_WhenSubmitted_ThenTheConfirmationIsInvalidDescribedAndFocused', async () => {
    serving({
      'POST /api/auth/refresh': { status: 200, body: signedInBody(MUST_CHANGE) },
      '/api/auth/session': { status: 200, body: MUST_CHANGE },
    });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });

    await fillChangePassword(screen, 'one invented password', 'a different one');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    const confirmation = screen.getByLabelText('New password again');
    await waitFor(() => expect(confirmation).toHaveAttribute('aria-invalid', 'true'));
    expect(confirmation).toHaveAccessibleDescription(
      expect.stringContaining('The two new passwords do not match.'),
    );
    expect(confirmation, 'focus on "New password again"').toHaveFocus();
  });

  it('GivenANewPasswordTheBackendRefuses_WhenShown_ThenTheNewPasswordIsInvalidAndDescribedByTheReason', async () => {
    const refusal = 'An invented refusal: that password is on a list of common ones.';
    serving({
      'POST /api/auth/refresh': { status: 200, body: signedInBody(MUST_CHANGE) },
      '/api/auth/session': { status: 200, body: MUST_CHANGE },
      'POST /api/auth/password': { status: 422, body: { detail: refusal } },
    });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });

    await fillChangePassword(screen);
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    const replacement = screen.getByLabelText('New password');
    await waitFor(() => expect(replacement).toHaveAttribute('aria-invalid', 'true'));
    expect(replacement).toHaveAccessibleDescription(expect.stringContaining(refusal));
  });
});

/**
 * "Your password has been changed." is about the change that was sent. Left on screen while the
 * fields are filled in again, it reads as being about what is being typed (#257, C9).
 */
describe('a success that does not outlive its edit', () => {
  it.each(['Current password', 'New password', 'New password again'])(
    'GivenAChangeFromThePasswordTab_WhenTypingIn %s Afterwards_ThenTheSuccessIsCleared',
    async (field) => {
      serving({
        'POST /api/auth/refresh': { status: 200, body: signedInBody() },
        '/api/auth/session': { status: 200, body: A_TENANT },
        'POST /api/auth/password': { status: 200, body: signedInBody() },
      });
      show();
      await userEvent.click(await screen.findByRole('button', { name: 'Password' }));
      await screen.findByRole('heading', { name: 'Change your password' });
      const form = within(screen.getByRole('main'));
      await fillChangePassword(form);
      await userEvent.click(form.getByRole('button', { name: 'Change password' }));
      await form.findByText('Your password has been changed.');

      await userEvent.type(form.getByLabelText(field), 'x', WITHOUT_PAUSES);

      expect(form.queryByText(/Your password has been changed/)).not.toBeInTheDocument();
    },
  );
});

/**
 * One refusal for every way a sign-in fails, and it says what somebody can do about it without
 * saying which of the reasons it was (#257).
 */
describe('what a refused sign-in suggests', () => {
  it('GivenASignInRefused_WhenShown_ThenItSaysWhatToDoNext', async () => {
    serving({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': {
        status: 401,
        body: { title: 'Sign-in refused', detail: ACCOUNT_LOCKED },
      },
    });
    show();

    await signInAs('nobody@example.com');

    const refusal = await screen.findByRole('alert');
    expect(refusal.textContent).toBe(
      'Those credentials were not accepted. Check your sign-in name and password. If they are right, ask an administrator.',
    );
    expect(refusal).not.toHaveTextContent(ACCOUNT_LOCKED);
  });
});

/**
 * The tab and the planning span were somebody's choices, and the last screen somebody saw hints at
 * the role they held. Whoever signs in next starts where anybody would (#257).
 */
describe('what a sign-out leaves for the next person', () => {
  it('GivenAnAccountThatChangedTheSpanOnAccounts_WhenAnotherAccountSignsInAfterItSignsOut_ThenItLandsOnPeopleWithTheDefaultSpan', async () => {
    const another: Session = {
      ...A_TENANT,
      userId: '77777777-7777-4777-8777-777777777777',
      signInName: 'grace@example.com',
    };
    serving({
      'POST /api/auth/refresh': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
      'POST /api/auth/sign-out': { status: 204, body: null },
      'POST /api/auth/sign-in': { status: 200, body: signedInBody(another) },
    });
    show();
    await userEvent.click(await screen.findByRole('button', { name: 'Accounts' }));
    // An invented date, far from today's span.
    fireEvent.change(screen.getByLabelText('Span from'), { target: { value: '2031-01-06' } });
    expect(screen.getByLabelText('Span from')).toHaveValue('2031-01-06');

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await signInAs(another.signInName);

    expect(await screen.findByRole('button', { name: 'People' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    const fresh = defaultSpan(new Date());
    expect(screen.getByLabelText('Span from')).toHaveValue(localDate(fresh.from));
    expect(screen.getByLabelText('Span to')).toHaveValue(localDate(fresh.to));
  });
});

/** An invented refusal of a new password, in words only the backend would choose. */
const COMMON_PASSWORD = 'An invented refusal: that password is on a list of common ones.';

/** The forced change, restored, with the password change answered as `answer`. */
function forcedChangeAnswering(answer: Answer) {
  return serving({
    'POST /api/auth/refresh': { status: 200, body: signedInBody(MUST_CHANGE) },
    '/api/auth/session': { status: 200, body: MUST_CHANGE },
    'POST /api/auth/password': answer,
  });
}

/**
 * The backend's refusal is the only place its password rule is stated, so it stays while the
 * password is written again, and goes when the next one is sent (#257, C6).
 */
describe('a refusal that stays until the next send', () => {
  it('GivenANewPasswordTheBackendRefused_WhenTypingInItAgain_ThenTheRefusalStaysAtTheField', async () => {
    forcedChangeAnswering({ status: 422, body: { detail: COMMON_PASSWORD } });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });
    await fillChangePassword(screen);
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
    const replacement = screen.getByLabelText('New password');
    await waitFor(() => expect(replacement).toHaveAttribute('aria-invalid', 'true'));

    await userEvent.type(replacement, 'x', WITHOUT_PAUSES);

    expect(replacement).toHaveAttribute('aria-invalid', 'true');
    expect(replacement).toHaveAccessibleDescription(expect.stringContaining(COMMON_PASSWORD));
    expect(screen.getByRole('alert')).toHaveTextContent(COMMON_PASSWORD);
  });

  it('GivenARefusalOnScreen_WhenTheFormIsSentAgain_ThenTheRefusalIsCleared', async () => {
    const routed = forcedChangeAnswering({ status: 422, body: { detail: COMMON_PASSWORD } });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });
    await fillChangePassword(screen);
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
    const replacement = screen.getByLabelText('New password');
    await waitFor(() => expect(replacement).toHaveAttribute('aria-invalid', 'true'));
    const release = holdingWrites(routed);

    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    try {
      expect(replacement).not.toHaveAttribute('aria-invalid');
      expect(replacement).not.toHaveAccessibleDescription(expect.stringContaining(COMMON_PASSWORD));
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    } finally {
      release();
      await screen.findByRole('alert');
    }
  });

  /**
   * The same refusal twice is a new alert, not the old one left in place: a screen reader announces
   * an alert as it appears, and says nothing of one whose text did not change.
   */
  it('GivenARefusalTheBackendRepeats_WhenSentAgain_ThenItIsRaisedAsANewAlert', async () => {
    forcedChangeAnswering({ status: 422, body: { detail: COMMON_PASSWORD } });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });
    await fillChangePassword(screen);
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
    const first = await screen.findByRole('alert');

    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() => expect(screen.getByRole('alert'), 'a new alert').not.toBe(first));
    expect(screen.getByRole('alert')).toHaveTextContent(COMMON_PASSWORD);
  });
});

/** A 403 to a password change is the current password the backend did not accept (#257, C6). */
describe('a wrong current password', () => {
  it('GivenACurrentPasswordTheBackendRefuses_WhenShown_ThenTheCurrentPasswordIsInvalidAndDescribedByTheReason', async () => {
    const refusal = 'The current password is not right.';
    forcedChangeAnswering({ status: 403, body: { detail: refusal } });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });

    await fillChangePassword(screen);
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    const current = screen.getByLabelText('Current password');
    await waitFor(() => expect(current).toHaveAttribute('aria-invalid', 'true'));
    expect(current).toHaveAccessibleDescription(expect.stringContaining(refusal));
    expect(screen.getByLabelText('New password')).not.toHaveAttribute('aria-invalid');
  });
});

/**
 * Focus moves to the confirmation, whose description is the mismatch, so the field is what says
 * it. An alert as well would say it twice (#257).
 */
describe('a mismatch said once', () => {
  it('GivenTwoNewPasswordsThatDiffer_WhenSubmitted_ThenNoAlertIsRaisedAndTheConfirmationsDescriptionSaysIt', async () => {
    forcedChangeAnswering({ status: 200, body: signedInBody() });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });

    await fillChangePassword(screen, 'one invented password', 'a different one');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    const confirmation = screen.getByLabelText('New password again');
    await waitFor(() => expect(confirmation).toHaveFocus());
    expect(confirmation).toHaveAccessibleDescription(
      expect.stringContaining('The two new passwords do not match.'),
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

/**
 * The button's own name changes while it is in flight, but a changed name on the focused control
 * is not reliably announced, so a status region already on the page says it (#257, C3).
 */
describe('what is said while a form sends', () => {
  it('GivenASignIn_WhenItIsInFlight_ThenAStatusAlreadyOnThePageSaysSigningIn', async () => {
    const { release } = holding(
      {
        'POST /api/auth/refresh': NO_COOKIE,
        'POST /api/auth/sign-in': { status: 200, body: signedInBody() },
        '/api/auth/session': { status: 200, body: A_TENANT },
      },
      'POST /api/auth/sign-in',
    );
    show();
    await screen.findByRole('heading', { name: 'Sign in' });
    const status = within(screen.getByRole('main')).getByRole('status');

    await signInAs(A_TENANT.signInName);

    try {
      expect(status).toHaveTextContent('Signing in…');
    } finally {
      release();
      await screen.findByRole('button', { name: 'People' });
    }
  });

  it('GivenAPasswordChange_WhenItIsInFlight_ThenAStatusAlreadyOnThePageSaysChangingYourPassword', async () => {
    const { release } = holding(
      {
        'POST /api/auth/refresh': { status: 200, body: signedInBody(MUST_CHANGE) },
        '/api/auth/session': { status: 200, body: MUST_CHANGE },
        'POST /api/auth/password': { status: 200, body: signedInBody() },
      },
      'POST /api/auth/password',
    );
    show();
    await screen.findByRole('heading', { name: 'Change your password' });
    const status = within(screen.getByRole('main')).getByRole('status');
    await fillChangePassword(screen);

    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    try {
      expect(status).toHaveTextContent('Changing your password…');
    } finally {
      release();
      await screen.findByRole('button', { name: 'People' });
    }
  });
});

/** A refusal ends the request it answered, so the form takes the next one (#257, C1). */
describe('a resubmit after a refusal', () => {
  it('GivenARefusedSignIn_WhenSubmittedAgain_ThenASecondSignInIsSent', async () => {
    const stub = serving({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': { status: 401, body: { detail: ACCOUNT_LOCKED } },
    });
    show();
    await signInAs('nobody@example.com');
    await screen.findByRole('alert');

    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() =>
      expect(callsTo(stub, 'POST', '/api/auth/sign-in'), 'the sign-ins sent').toHaveLength(2),
    );
  });

  it('GivenARefusedPasswordChange_WhenSubmittedAgain_ThenASecondChangeIsSent', async () => {
    const stub = forcedChangeAnswering({ status: 422, body: { detail: COMMON_PASSWORD } });
    show();
    await screen.findByRole('heading', { name: 'Change your password' });
    await fillChangePassword(screen);
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));
    await screen.findByRole('alert');

    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() =>
      expect(callsTo(stub, 'POST', '/api/auth/password'), 'the changes sent').toHaveLength(2),
    );
  });
});

/**
 * A renewal for the account on screen changes nothing anybody is looking at, so it moves nothing:
 * somebody typing carries on where they were (#257, C4).
 */
describe('a renewal while somebody types', () => {
  it('GivenSomebodyTypingInAField_WhenTheSameAccountsSessionIsRenewed_ThenFocusStaysInThatField', async () => {
    serving({
      'POST /api/auth/refresh': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
    });
    show();
    await userEvent.click(await screen.findByRole('button', { name: 'Password' }));
    await screen.findByRole('heading', { name: 'Change your password' });
    const current = screen.getByLabelText('Current password');
    await userEvent.type(current, PASSWORD, WITHOUT_PAUSES);

    act(() => {
      holdSession(signedInBody());
    });

    expect(current, 'focus on "Current password"').toHaveFocus();
    expect(current).toHaveValue(PASSWORD);
  });
});
