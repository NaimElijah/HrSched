import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { currentAccessToken, endSession } from '../api/session';
import type { Session } from '../api/session';
import { SessionProvider } from './SessionProvider';
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

interface Answer {
  readonly status: number;
  readonly body: unknown;
}

/** Routes the handshake and whatever else a test names, and lets the answers change mid-test. */
function serving(answers: () => Record<string, Answer>) {
  const stub = vi.fn((path: string, init?: RequestInit) => {
    const table = answers();
    const answer: Answer = table[`${init?.method ?? 'GET'} ${path}`] ??
      table[path] ?? { status: 200, body: [] };
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
    serving(() => ({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
    }));
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
    const stub = serving(() => ({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
    }));
    show();

    await userEvent.type(
      await screen.findByLabelText('Sign-in name'),
      'ada@example.com',
      WITHOUT_PAUSES,
    );
    await userEvent.type(screen.getByLabelText('Password'), PASSWORD, WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await screen.findByRole('button', { name: 'People' });
    const sent = stub.mock.calls.filter(
      ([path, init]) => path === '/api/auth/sign-in' && init?.method === 'POST',
    );
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
    serving(() => ({
      'POST /api/auth/refresh': NO_COOKIE,
      'POST /api/auth/sign-in': {
        status: 401,
        body: { title: 'Sign-in refused', detail: 'Those credentials were not accepted.' },
      },
    }));
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
    serving(() => ({
      'POST /api/auth/refresh': { status: 200, body: signedInBody() },
      '/api/auth/session': { status: 200, body: A_TENANT },
      'POST /api/auth/sign-out': { status: 204, body: null },
    }));
    show();
    await screen.findByRole('button', { name: 'People' });

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(currentAccessToken()).toBeNull();
  });
});

describe('changing a password', () => {
  it('GivenAChangedPassword_WhenAccepted_ThenTheShellIsShown', async () => {
    const stub = serving(() => ({
      'POST /api/auth/refresh': {
        status: 200,
        body: signedInBody({ ...A_TENANT, passwordChangeRequired: true }),
      },
      '/api/auth/session': { status: 200, body: { ...A_TENANT, passwordChangeRequired: true } },
      'POST /api/auth/password': { status: 200, body: signedInBody() },
    }));
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
    const sent = stub.mock.calls.filter(
      ([path, init]) => path === '/api/auth/password' && init?.method === 'POST',
    );
    expect(sent, 'the password changes sent').toHaveLength(1);
    expect(JSON.parse(String(sent[0]?.[1]?.body))).toEqual({
      currentPassword: PASSWORD,
      newPassword: 'another invented password',
    });
  });

  it('GivenTwoNewPasswordsThatDiffer_WhenSubmitted_ThenNothingIsSent', async () => {
    const stub = serving(() => ({
      'POST /api/auth/refresh': {
        status: 200,
        body: signedInBody({ ...A_TENANT, passwordChangeRequired: true }),
      },
      '/api/auth/session': { status: 200, body: { ...A_TENANT, passwordChangeRequired: true } },
    }));
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
    serving(() => ({
      'POST /api/auth/refresh': {
        status: 200,
        body: signedInBody({ ...A_TENANT, passwordChangeRequired: true }),
      },
      '/api/auth/session': { status: 200, body: { ...A_TENANT, passwordChangeRequired: true } },
      'POST /api/auth/password': {
        status: 422,
        body: { detail: 'A password must be at least 15 characters long.', reason: 'TOO_SHORT' },
      },
    }));
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
    serving(() => ({
      'POST /api/auth/refresh': {
        status: 200,
        body: signedInBody({ ...A_TENANT, passwordChangeRequired: true }),
      },
      '/api/auth/session': { status: 200, body: { ...A_TENANT, passwordChangeRequired: true } },
    }));
    show();

    await screen.findByRole('heading', { name: 'Change your password' });

    expect(screen.getByLabelText('New password')).toHaveValue('');
    expect(screen.getByLabelText('New password again')).toHaveValue('');
  });
});

describe('a session that ends underneath somebody', () => {
  it('GivenTheSessionEnds_WhenARequestIsRefused_ThenTheSignInPageIsShown', async () => {
    let live = true;
    serving(() =>
      live
        ? {
            'POST /api/auth/refresh': { status: 200, body: signedInBody() },
            '/api/auth/session': { status: 200, body: A_TENANT },
          }
        : {
            'POST /api/auth/refresh': NO_COOKIE,
            '/api/employees': NO_COOKIE,
            '/api/qualifications': NO_COOKIE,
          },
    );
    show();
    await screen.findByRole('button', { name: 'People' });

    live = false;
    await userEvent.click(screen.getByRole('button', { name: 'Qualifications' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    await waitFor(() => expect(currentAccessToken()).toBeNull());
  });
});
