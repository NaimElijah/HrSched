import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { currentAccessToken, endSession, holdSession } from '../api/session';
import type { Session } from '../api/session';
import { SessionProvider } from './SessionProvider';
import { callsTo, routeTo } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import { A_TENANT } from '../test-support/session';
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
        body: { title: 'Sign-in refused', detail: 'Those credentials were not accepted.' },
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
    const heading = await screen.findByRole('heading', { name: 'Change your password' });
    const form = within(heading.closest('section') as HTMLElement);

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
    const heading = await screen.findByRole('heading', { name: 'Change your password' });
    const form = within(heading.closest('section') as HTMLElement);

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

    expect(await screen.findByRole('alert')).toHaveTextContent('do not match');
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
