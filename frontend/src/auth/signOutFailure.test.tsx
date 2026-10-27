import { act, render, screen, waitFor, within } from '@testing-library/react';
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

/** What one sign-out attempt is answered with, optionally with response headers. */
type SignOutAnswer = (Answer & { readonly headers?: Record<string, string> }) | typeof UNREACHABLE;

/** The retry, named for what it retries. */
const RETRY = /sign(ing)? out again/i;

/** That this browser may still be signed in. */
const STILL_SIGNED_IN = /still (be )?signed in/i;

/**
 * The whole warning, in one alert: the sign-out did not happen, and this browser may still be
 * signed in. Lookaheads, so that neither the order nor the exact words are pinned.
 */
const THE_WARNING =
  /^(?=.*sign[- ]?out)(?=.*(\bnot\b|n't\b|\bfailed\b|\bunable\b))(?=.*still (be )?signed in)/is;

/** A heading that says the sign-out failed, in whatever words. */
const SIGN_OUT_FAILED_HEADING =
  /^(?=.*sign[- ]?out)(?=.*(\bnot\b|n't\b|\bfailed\b|\bunconfirmed\b|\bunable\b|\bincomplete\b))/i;

/** That the server did not confirm the sign-out. */
const NOT_CONFIRMED = /(\bnot\b|n't)\s+(yet\s+)?(been\s+|be\s+)?confirm|\bunconfirmed\b/i;

/** A status that says the sign-out is being tried again. */
const RETRYING = /^(?=.*sign[- ]?(ing[- ])?out)(?=.*(\bagain\b|\bretry|\btrying\b))/is;

/**
 * A backend whose refresh cookie belongs to a signed-in Admin, and whose sign-out answers whatever
 * the test says on each attempt.
 */
function signedInWhoseSignOutAnswers(signOut: () => SignOutAnswer | Promise<SignOutAnswer>) {
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
    return Promise.resolve(signOut()).then((answer) => {
      if (answer === UNREACHABLE) {
        throw new TypeError('Failed to fetch');
      }
      return {
        ok: answer.status < 400,
        status: answer.status,
        headers: new Headers(answer.headers ?? {}),
        text: () => Promise.resolve(answer.body === null ? '' : JSON.stringify(answer.body)),
      };
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
    'GivenASignOutThatFails_WhenSigningOut_ThenTheUserIsWarnedTheyMayStillBeSignedIn ($failure)',
    async ({ answer }) => {
      signedInWhoseSignOutAnswers(() => answer);
      await showSignedIn();

      await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

      await expectTheStillSignedInWarning();
      expect(screen.getByRole('button', { name: RETRY })).toBeInTheDocument();
    },
  );

  it.each<{ failure: string; answer: SignOutAnswer }>([
    { failure: 'a gateway 502', answer: { status: 502, body: null } },
    { failure: 'an unreachable network', answer: UNREACHABLE },
  ])(
    'GivenASignOutThatFails_WhenTheWarningIsShown_ThenTheSignInFormIsNotOffered ($failure)',
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

    await userEvent.click(screen.getByRole('button', { name: RETRY }));

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

    await userEvent.click(screen.getByRole('button', { name: RETRY }));

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

    await userEvent.click(screen.getByRole('button', { name: RETRY }));

    await waitFor(() => expect(callsTo(stub, 'POST', SIGN_OUT)).toHaveLength(2));
    await expectTheStillSignedInWarning();
    expect(screen.getByRole('button', { name: RETRY })).toBeInTheDocument();
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

const FAILED: SignOutAnswer = { status: 502, body: null };

/** An answer the test holds back, to look at the screen while the request is still in flight. */
function anAnswerHeldBack() {
  let answer: (given: SignOutAnswer) => void = () => undefined;
  const answered = new Promise<SignOutAnswer>((resolve) => {
    answer = resolve;
  });
  return { answered, answer };
}

/** Signs in, signs out into a failure, and leaves the warning on screen. */
async function showTheWarning(retry: () => SignOutAnswer | Promise<SignOutAnswer> = () => FAILED) {
  let attempts = 0;
  const stub = signedInWhoseSignOutAnswers(() => {
    attempts += 1;
    return attempts === 1 ? FAILED : retry();
  });
  await showSignedIn();
  await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
  await expectTheStillSignedInWarning();
  return stub;
}

function statusesShown(): HTMLElement[] {
  return screen.queryAllByRole('status');
}

/**
 * The warning is a screen of its own, and has to be found as one: somebody using a screen reader
 * or a keyboard was on the shell a moment ago, and their focus was on a button that no longer
 * exists. The screen says what happened in its heading, takes focus there, and lives in the page's
 * main landmark like every other screen.
 */
describe('the warning screen', () => {
  it('GivenAFailedSignOut_WhenTheWarningAppears_ThenItsHeadingSaysSoAndTakesFocus', async () => {
    await showTheWarning();

    const heading = await screen.findByRole('heading', { name: SIGN_OUT_FAILED_HEADING });

    await waitFor(() => expect(heading, 'focus on the warning screen heading').toHaveFocus());
  });

  it('GivenAFailedSignOut_WhenTheWarningIsShown_ThenItSitsInsideTheMainLandmark', async () => {
    await showTheWarning();

    const main = screen.getByRole('main');

    expect(
      within(main).getByRole('heading', { name: SIGN_OUT_FAILED_HEADING }),
    ).toBeInTheDocument();
    expect(
      within(main)
        .queryAllByRole('alert')
        .map((alert) => alert.textContent ?? ''),
      'the warning inside main',
    ).toContainEqual(expect.stringMatching(THE_WARNING));
    expect(within(main).getByRole('button', { name: RETRY })).toBeInTheDocument();
  });

  it('GivenAFailedSignOut_WhenTheWarningIsShown_ThenTheRetryIsNamedForWhatItRetries', async () => {
    await showTheWarning();

    expect(screen.getByRole('button', { name: RETRY })).toBeInTheDocument();
  });

  /**
   * "Did not reach the server" is a claim about the network the app cannot make: a 500 or 502 did
   * reach something. What it does know is that nothing confirmed the sign-out.
   */
  it('GivenAFailedSignOut_WhenTheWarningIsShown_ThenItSaysTheServerDidNotConfirmIt', async () => {
    await showTheWarning();

    const warning = warningsShown().find((text) => THE_WARNING.test(text)) ?? '';
    expect(warning).toMatch(NOT_CONFIRMED);
    expect(warning).not.toMatch(/\breach/i);
  });

  it('GivenASignOutRefusedWithATraceId_WhenTheWarningIsShown_ThenTheReferenceIsShown', async () => {
    signedInWhoseSignOutAnswers(() => ({
      status: 500,
      body: { detail: 'Something broke.' },
      headers: { 'X-Trace-Id': 'trace-sign-out-9' },
    }));
    await showSignedIn();

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await expectTheStillSignedInWarning();

    expect(screen.getByText(/trace-sign-out-9/)).toBeInTheDocument();
  });
});

describe('a retry of the sign-out', () => {
  it('GivenARetryInFlight_WhenItHasNotAnswered_ThenAStatusSaysTheSignOutIsBeingRetried', async () => {
    const retry = anAnswerHeldBack();
    await showTheWarning(() => retry.answered);

    await userEvent.click(screen.getByRole('button', { name: RETRY }));

    try {
      await waitFor(() =>
        expect(
          statusesShown().map((status) => status.textContent ?? ''),
          'a status saying the sign-out is being retried',
        ).toContainEqual(expect.stringMatching(RETRYING)),
      );
    } finally {
      await act(async () => retry.answer({ status: 204, body: null }));
    }
  });

  it('GivenARetryInFlight_WhenTheRetryIsClickedAgain_ThenNoSecondRequestIsSent', async () => {
    const retry = anAnswerHeldBack();
    const stub = await showTheWarning(() => retry.answered);
    await userEvent.click(screen.getByRole('button', { name: RETRY }));
    await waitFor(() => expect(callsTo(stub, 'POST', SIGN_OUT)).toHaveLength(2));

    try {
      await userEvent.click(screen.getByRole('button', { name: RETRY }));

      expect(callsTo(stub, 'POST', SIGN_OUT), 'the first sign-out and one retry').toHaveLength(2);
    } finally {
      await act(async () => retry.answer({ status: 204, body: null }));
    }
  });

  /**
   * A live region announces a change, and only a change. A retry that fails the same way as the
   * first attempt has to say something new, or nobody listening hears that it failed.
   */
  it('GivenARetryThatFailsAgain_WhenItAnswers_ThenTheStatusChangesToADifferentMessage', async () => {
    const retry = anAnswerHeldBack();
    await showTheWarning(() => retry.answered);
    await userEvent.click(screen.getByRole('button', { name: RETRY }));
    const status = await waitFor(() => {
      const found = statusesShown().find((candidate) => RETRYING.test(candidate.textContent ?? ''));
      expect(found, 'a status saying the sign-out is being retried').toBeDefined();
      return found as HTMLElement;
    });
    const whileRetrying = status.textContent;

    await act(async () => retry.answer(FAILED));

    expect(status, 'the same live region, still on screen').toBeInTheDocument();
    expect(status.textContent?.trim(), 'the status after the retry failed').not.toBe('');
    expect(status.textContent).not.toBe(whileRetrying);
  });
});
