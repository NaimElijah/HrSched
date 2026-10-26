import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { auth } from '../api/client';
import { currentAccessToken, endSession, holdSession } from '../api/session';
import { callsTo, routeTo } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import { A_TENANT } from '../test-support/session';
import { SessionProvider } from './SessionProvider';

/**
 * A sign-out that did not reach the backend has not happened.
 *
 * The backend revokes the session and clears the refresh cookie only when the sign-out succeeds.
 * Until then the cookie is still in this browser, and the next reload restores the session from it:
 * on a shared computer, the next person to open the app is the last one. Forgetting the token here
 * is still right, but showing the plain sign-in form would tell somebody walking away that they had
 * signed out when they had not (#250). So a failed sign-out says so, and offers to try again.
 *
 * The wording is the UI's to choose; what is pinned is the meaning.
 */

const SIGN_OUT = '/api/auth/sign-out';

/** How `fetch` fails when the network is down: it rejects rather than answering. */
const UNREACHABLE = 'unreachable';

type SignOutAnswer = Answer | typeof UNREACHABLE;

/** That this browser may still be signed in. */
const STILL_SIGNED_IN = /still (be )?signed in/i;

/**
 * The whole warning, in one alert: the sign-out did not happen, and this browser may still be
 * signed in. Lookaheads, so that neither the order nor the exact words are pinned.
 */
const THE_WARNING = /^(?=.*sign[- ]?out)(?=.*(not|n't|failed|unable))(?=.*still (be )?signed in)/is;

/**
 * A backend whose refresh cookie belongs to a signed-in Admin, and whose sign-out answers whatever
 * the test says on each attempt.
 */
function signedInWhoseSignOutAnswers(signOut: () => SignOutAnswer) {
  const routed = routeTo({
    'POST /api/auth/refresh': {
      status: 200,
      body: { accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 600, session: A_TENANT },
    },
    'GET /api/auth/session': { status: 200, body: A_TENANT },
    'GET /api/employees': { status: 200, body: [] },
    'GET /api/qualifications': { status: 200, body: [] },
  });
  const stub = vi.fn((path: string, init?: RequestInit) => {
    if (path !== SIGN_OUT) {
      return routed(path, init);
    }
    const answer = signOut();
    if (answer === UNREACHABLE) {
      return Promise.reject(new TypeError('Failed to fetch'));
    }
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

async function showSignedIn() {
  render(
    <SessionProvider>
      <App />
    </SessionProvider>,
  );
  await screen.findByRole('button', { name: 'Sign out' });
}

function warningsShown(): string[] {
  return screen.queryAllByRole('alert').map((alert) => alert.textContent ?? '');
}

/** Waits for an alert that says the sign-out failed and this browser may still be signed in. */
async function expectTheStillSignedInWarning() {
  await waitFor(() =>
    expect(
      warningsShown(),
      'an alert saying the sign-out failed and this browser may still be signed in',
    ).toContainEqual(expect.stringMatching(THE_WARNING)),
  );
}

/**
 * The warning replaces the sign-in form rather than sitting above it: a form on screen reads as
 * "signed out", which is exactly what has not been confirmed.
 */
function expectNoSignInForm() {
  expect(
    screen.queryByRole('heading', { name: 'Sign in' }),
    'no sign-in heading while the warning is shown',
  ).not.toBeInTheDocument();
  expect(
    screen.queryByLabelText('Sign-in name'),
    'no sign-in-name field while the warning is shown',
  ).not.toBeInTheDocument();
}

afterEach(() => {
  vi.unstubAllGlobals();
  endSession();
});

describe('a sign-out the backend did not confirm', () => {
  it.each<{ failure: string; answer: SignOutAnswer }>([
    { failure: 'a gateway 502', answer: { status: 502, body: null } },
    { failure: 'a server 500', answer: { status: 500, body: { detail: 'Something broke.' } } },
    { failure: 'an unreachable network', answer: UNREACHABLE },
  ])(
    'GivenASignOutThatFails ($failure)_WhenSigningOut_ThenTheUserIsWarnedTheyMayStillBeSignedIn',
    async ({ answer }) => {
      signedInWhoseSignOutAnswers(() => answer);
      await showSignedIn();

      await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

      await expectTheStillSignedInWarning();
      expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
    },
  );

  it.each<{ failure: string; answer: SignOutAnswer }>([
    { failure: 'a gateway 502', answer: { status: 502, body: null } },
    { failure: 'an unreachable network', answer: UNREACHABLE },
  ])(
    'GivenASignOutThatFails ($failure)_WhenTheWarningIsShown_ThenTheSignInFormIsNotOffered',
    async ({ answer }) => {
      signedInWhoseSignOutAnswers(() => answer);
      await showSignedIn();

      await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

      await expectTheStillSignedInWarning();
      expectNoSignInForm();
    },
  );

  /** Whatever the backend did, this tab must not go on presenting the token. */
  it('GivenASignOutThatFails_WhenSigningOut_ThenTheTokenIsStillForgottenHere', async () => {
    signedInWhoseSignOutAnswers(() => ({ status: 502, body: null }));
    await showSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    await expectTheStillSignedInWarning();
    expect(currentAccessToken()).toBeNull();
  });

  it('GivenAFailedSignOut_WhenTryingAgainAndItSucceeds_ThenTheSignInPageIsShownWithoutTheWarning', async () => {
    let attempts = 0;
    signedInWhoseSignOutAnswers(() => {
      attempts += 1;
      return attempts === 1 ? { status: 502, body: null } : { status: 204, body: null };
    });
    await showSignedIn();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await expectTheStillSignedInWarning();
    expectNoSignInForm();

    await userEvent.click(screen.getByRole('button', { name: /try again/i }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByLabelText('Sign-in name')).toBeInTheDocument();
    expect(warningsShown().filter((text) => STILL_SIGNED_IN.test(text))).toEqual([]);
  });

  /** The retry is the same request: credentialed by the cookie, never by the forgotten token. */
  it('GivenAFailedSignOut_WhenTryingAgain_ThenTheSignOutIsSentAgainCredentialedByTheCookie', async () => {
    let attempts = 0;
    const stub = signedInWhoseSignOutAnswers(() => {
      attempts += 1;
      return attempts === 1 ? UNREACHABLE : { status: 204, body: null };
    });
    await showSignedIn();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await expectTheStillSignedInWarning();

    await userEvent.click(screen.getByRole('button', { name: /try again/i }));

    await waitFor(() => expect(callsTo(stub, 'POST', SIGN_OUT)).toHaveLength(2));
    const retry = callsTo(stub, 'POST', SIGN_OUT)[1]?.[1] as RequestInit;
    const headers = new Headers(retry.headers);
    expect(headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    expect(headers.get('Authorization')).toBeNull();
    expect(retry.credentials).toBe('same-origin');
  });

  it('GivenAFailedSignOut_WhenTheRetryFailsToo_ThenTheWarningRemains', async () => {
    const stub = signedInWhoseSignOutAnswers(() => ({ status: 502, body: null }));
    await showSignedIn();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await expectTheStillSignedInWarning();

    await userEvent.click(screen.getByRole('button', { name: /try again/i }));

    await waitFor(() => expect(callsTo(stub, 'POST', SIGN_OUT)).toHaveLength(2));
    await expectTheStillSignedInWarning();
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
    expectNoSignInForm();
  });
});

describe('a sign-out the backend confirmed', () => {
  it('GivenASignOutThatSucceeds_WhenSigningOut_ThenNoStillSignedInWarningIsShown', async () => {
    signedInWhoseSignOutAnswers(() => ({ status: 204, body: null }));
    await showSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(warningsShown().filter((text) => STILL_SIGNED_IN.test(text))).toEqual([]);
  });
});

/**
 * Somebody who clicks "Sign out" and closes the tab at once has asked to sign out. Without
 * `keepalive` the browser may abort the request as the page unloads, and the cookie survives.
 */
describe('a sign-out sent as the tab closes', () => {
  it('GivenASignOut_WhenSent_ThenItAsksToOutliveThePage', async () => {
    holdSession({ accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 600, session: A_TENANT });
    const stub = routeTo({ [`POST ${SIGN_OUT}`]: { status: 204, body: null } });

    await auth.signOut();

    const sent = callsTo(stub, 'POST', SIGN_OUT)[0]?.[1];
    expect(sent?.keepalive, 'the sign-out request should be sent with keepalive').toBe(true);
  });
});
