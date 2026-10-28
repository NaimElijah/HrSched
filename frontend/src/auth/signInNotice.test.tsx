import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { endSession } from '../api/session';
import { routeTo } from '../test-support/routeTo';
import { A_TENANT } from '../test-support/session';
import { SessionProvider } from './SessionProvider';

/**
 * A sign-in page that appears in place of the shell says why.
 *
 * Somebody whose session ended underneath them — another tab signed out, or the backend stopped
 * accepting it — was looking at a screen a moment ago. A sign-in form with no explanation reads as
 * the app having failed, and a screen-reader user hears nothing at all unless focus moves. So the
 * page gives the reason in a status region and takes focus on its heading. On a first load there
 * is nothing to explain, and focus is left where the browser puts it.
 */

const CHANNEL = 'hrsched.session';

/** A reason for being signed out, in whatever words. */
const A_REASON =
  /\banother (tab|window)\b|\bsigned out\b|\bsession (has |was )?(ended|expired)\b|\bsession is over\b/i;

/** Channels a test opened to play another tab, closed before the next test. */
const opened: BroadcastChannel[] = [];

async function showSignedIn() {
  routeTo({
    'POST /api/auth/refresh': {
      status: 200,
      body: { accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 600, session: A_TENANT },
    },
    'GET /api/auth/session': { status: 200, body: A_TENANT },
    'GET /api/employees': { status: 200, body: [] },
    'GET /api/qualifications': { status: 200, body: [] },
  });
  render(
    <SessionProvider>
      <App />
    </SessionProvider>,
  );
  await screen.findByRole('button', { name: 'Sign out' });
}

function reasonsShown(): string[] {
  return screen.queryAllByRole('status').map((status) => status.textContent ?? '');
}

async function expectAReasonAndFocusOnTheHeading() {
  const heading = await screen.findByRole('heading', { name: 'Sign in' });
  await waitFor(() =>
    expect(reasonsShown(), 'a status saying why the sign-in page is shown').toContainEqual(
      expect.stringMatching(A_REASON),
    ),
  );
  await waitFor(() => expect(heading, 'focus on the "Sign in" heading').toHaveFocus());
}

afterEach(() => {
  for (const channel of opened.splice(0)) {
    channel.close();
  }
  vi.unstubAllGlobals();
  endSession();
});

describe('a sign-in page after a session ended', () => {
  it('GivenASignedInTab_WhenAnotherTabEndsTheSession_ThenTheSignInPageSaysWhyAndFocusesItsHeading', async () => {
    await showSignedIn();
    const anotherTab = new BroadcastChannel(CHANNEL);
    opened.push(anotherTab);

    anotherTab.postMessage('ended');

    await expectAReasonAndFocusOnTheHeading();
  });

  /** The client ends the session this way when the backend refuses the refresh cookie. */
  it('GivenASignedInTab_WhenTheSessionEnds_ThenTheSignInPageSaysWhyAndFocusesItsHeading', async () => {
    await showSignedIn();

    act(() => endSession());

    await expectAReasonAndFocusOnTheHeading();
  });
});

describe('a sign-in page on a first load', () => {
  it('GivenNoSession_WhenTheAppFirstLoads_ThenTheSignInPageGivesNoReasonAndTakesNoFocus', async () => {
    routeTo({
      'POST /api/auth/refresh': {
        status: 401,
        body: { detail: 'A valid access token is required.' },
      },
    });
    render(
      <SessionProvider>
        <App />
      </SessionProvider>,
    );

    const heading = await screen.findByRole('heading', { name: 'Sign in' });

    expect(reasonsShown().filter((text) => A_REASON.test(text))).toEqual([]);
    expect(heading).not.toHaveFocus();
  });
});
