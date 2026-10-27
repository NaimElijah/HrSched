import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { api } from '../api/client';
import { currentAccessToken, currentSession, endSession, holdSession } from '../api/session';
import type { Session } from '../api/session';
import { routeTo } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import { A_TENANT } from '../test-support/session';
import { SessionProvider } from './SessionProvider';

/**
 * Signing out in one tab signs out every tab.
 *
 * The token lives in each tab's memory, so ending it in one leaves every other tab still drawing
 * what it had loaded: names, absences, contracted hours. On a shared computer the next person finds
 * those tabs open (#250). Tabs tell each other over a `BroadcastChannel` named `hrsched.session`:
 * a tab whose session ends posts `'ended'`, and a tab that hears it ends its own session without
 * posting again — two tabs answering each other would never stop.
 *
 * The other tab is played by a second channel of the same name. The test environment has Node's
 * own `BroadcastChannel`, which delivers between instances in one process exactly as a browser
 * delivers between tabs, and never to the instance that posted.
 *
 * A sign-out the backend has not confirmed is a different message. Every tab forgets its token at
 * once, before the backend answers, because the person may already be walking away; but each says
 * the browser may still be signed in rather than showing the sign-in form, until a confirmed
 * sign-out, from any tab, says `'ended'`.
 *
 * The employee and accounts are invented.
 */

const CHANNEL = 'hrsched.session';
const ENDED = 'ended';
const SIGN_OUT_UNCONFIRMED = 'sign-out-unconfirmed';
const SIGN_OUT = '/api/auth/sign-out';

/** The warning, by meaning: the sign-out, and a browser that may still be signed in. */
const THE_WARNING = /^(?=.*sign[- ]?out)(?=.*still (be )?signed in)/is;

/** The retry, under the name it has or the one it is to have. */
const RETRY = /try again|sign(ing)? out again/i;

/** Somebody else, whom the shared refresh cookie may renew as after they sign in in another tab. */
const ANOTHER_ACCOUNT: Session = {
  ...A_TENANT,
  userId: '66666666-6666-4666-8666-666666666666',
  signInName: 'grace@example.com',
};

const AN_EMPLOYEE = {
  id: 'e-1',
  name: 'Ada Example',
  qualifications: [],
  unavailability: [],
  weeklyContractedMinutes: null,
};

/** The channels a test opened, closed before the session is ended between tests. */
const opened: BroadcastChannel[] = [];

function anotherTab(): BroadcastChannel {
  const channel = new BroadcastChannel(CHANNEL);
  opened.push(channel);
  return channel;
}

function respond(answer: Answer) {
  return {
    ok: answer.status < 400,
    status: answer.status,
    headers: new Headers(),
    text: () => Promise.resolve(answer.body === null ? '' : JSON.stringify(answer.body)),
  };
}

/**
 * A tab signed in as an Admin, on the People page, showing an employee's name.
 *
 * @param signOut how each sign-out is answered; a promise that never settles is one still in flight
 */
async function showThePeoplePageSignedIn(
  signOut: () => Promise<Answer> = () => Promise.resolve({ status: 204, body: null }),
) {
  const routed = routeTo({
    'POST /api/auth/refresh': {
      status: 200,
      body: { accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 600, session: A_TENANT },
    },
    'GET /api/auth/session': { status: 200, body: A_TENANT },
    'GET /api/employees': { status: 200, body: [AN_EMPLOYEE] },
    'GET /api/qualifications': { status: 200, body: [] },
  });
  vi.stubGlobal(
    'fetch',
    vi.fn((path: string, init?: RequestInit) =>
      path === SIGN_OUT ? signOut().then(respond) : routed(path, init),
    ),
  );
  render(
    <SessionProvider>
      <App />
    </SessionProvider>,
  );
  await screen.findByText(AN_EMPLOYEE.name);
}

/** A channel playing another tab, recording everything it hears. */
function aListeningTab() {
  const heard: unknown[] = [];
  anotherTab().onmessage = (event: MessageEvent) => {
    heard.push(event.data);
  };
  return heard;
}

/** Records what this tab posts, leaving out the channels the test plays other tabs with. */
function recordWhatThisTabPosts() {
  const posted = vi.spyOn(BroadcastChannel.prototype, 'postMessage');
  return () =>
    posted.mock.calls
      .filter((_, index) => !opened.includes(posted.mock.contexts[index] as BroadcastChannel))
      .map(([message]) => message as unknown);
}

async function expectTheWarning() {
  await waitFor(() =>
    expect(
      screen.queryAllByRole('alert').map((alert) => alert.textContent ?? ''),
      'an alert saying the sign-out is not confirmed and this browser may still be signed in',
    ).toContainEqual(expect.stringMatching(THE_WARNING)),
  );
}

function warningsLeft(): HTMLElement[] {
  return screen
    .queryAllByRole('alert')
    .filter((alert) => THE_WARNING.test(alert.textContent ?? ''));
}

function expectNoSignInForm() {
  expect(screen.queryByRole('heading', { name: 'Sign in' }), 'no sign-in heading').toBeNull();
  expect(screen.queryByLabelText('Sign-in name'), 'no sign-in-name field').toBeNull();
}

const FAILED: Answer = { status: 502, body: null };
const CONFIRMED: Answer = { status: 204, body: null };

afterEach(() => {
  for (const channel of opened.splice(0)) {
    channel.close();
  }
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  endSession();
});

describe('another tab signing out', () => {
  it('GivenASignedInTabShowingPersonalData_WhenAnotherTabSaysTheSessionEnded_ThenTheSignInPageReplacesIt', async () => {
    await showThePeoplePageSignedIn();

    anotherTab().postMessage(ENDED);

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByText(AN_EMPLOYEE.name)).not.toBeInTheDocument();
    expect(currentAccessToken()).toBeNull();
    expect(currentSession()).toBeNull();
  });

  it('GivenASignedInTab_WhenAnotherTabSaysTheSessionEnded_ThenItDoesNotPostAgain', async () => {
    await showThePeoplePageSignedIn();
    const other = anotherTab();
    const posted = vi.spyOn(BroadcastChannel.prototype, 'postMessage');

    other.postMessage(ENDED);
    await screen.findByRole('heading', { name: 'Sign in' });

    const echoes = posted.mock.contexts.filter((channel) => channel !== other);
    expect(echoes, 'a tab that heard the session end must not announce it again').toHaveLength(0);
  });
});

describe('this tab signing out', () => {
  it('GivenASignedInTab_WhenItsSessionEnds_ThenOtherTabsAreToldItEnded', async () => {
    const heard: unknown[] = [];
    anotherTab().onmessage = (event: MessageEvent) => {
      heard.push(event.data);
    };
    holdSession({ accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 600, session: A_TENANT });

    endSession();

    await waitFor(() => expect(heard, `what other tabs heard on ${CHANNEL}`).toEqual([ENDED]));
  });
});

describe('a sign-out the backend did not confirm', () => {
  it('GivenASignOutThatFails_WhenSigningOut_ThenOtherTabsAreToldItIsUnconfirmedRatherThanEnded', async () => {
    await showThePeoplePageSignedIn(() => Promise.resolve(FAILED));
    const heard = aListeningTab();
    const postedHere = recordWhatThisTabPosts();

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await expectTheWarning();

    await waitFor(() =>
      expect(heard, `what other tabs heard on ${CHANNEL}`).toContain(SIGN_OUT_UNCONFIRMED),
    );
    expect(postedHere(), 'an unconfirmed sign-out must not tell other tabs it ended').not.toContain(
      ENDED,
    );
  });

  it('GivenASignedInTab_WhenAnotherTabSaysItsSignOutIsUnconfirmed_ThenItForgetsTheSessionAndWarnsWithoutTheSignInForm', async () => {
    await showThePeoplePageSignedIn();

    anotherTab().postMessage(SIGN_OUT_UNCONFIRMED);

    await expectTheWarning();
    expectNoSignInForm();
    expect(screen.queryByText(AN_EMPLOYEE.name)).not.toBeInTheDocument();
    expect(currentAccessToken()).toBeNull();
    expect(currentSession()).toBeNull();
  });

  it('GivenAnUnconfirmedSignOutHere_WhenItsRetryIsConfirmed_ThenOtherTabsAreToldItEnded', async () => {
    let attempts = 0;
    await showThePeoplePageSignedIn(() => {
      attempts += 1;
      return Promise.resolve(attempts === 1 ? FAILED : CONFIRMED);
    });
    const heard = aListeningTab();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await expectTheWarning();

    await userEvent.click(screen.getByRole('button', { name: RETRY }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    await waitFor(() => expect(heard.at(-1), 'the last thing other tabs heard').toBe(ENDED));
    expect(warningsLeft()).toEqual([]);
  });

  it('GivenAnUnconfirmedSignOutHere_WhenAnotherTabConfirmsASignOut_ThenTheWarningGivesWayToTheSignInPage', async () => {
    await showThePeoplePageSignedIn(() => Promise.resolve(FAILED));
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    await expectTheWarning();

    anotherTab().postMessage(ENDED);

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(warningsLeft()).toEqual([]);
  });

  it('GivenATabWarnedByAnotherTab_WhenASignOutIsConfirmed_ThenTheWarningGivesWayToTheSignInPage', async () => {
    await showThePeoplePageSignedIn();
    const other = anotherTab();
    other.postMessage(SIGN_OUT_UNCONFIRMED);
    await expectTheWarning();

    other.postMessage(ENDED);

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(warningsLeft()).toEqual([]);
  });
});

/**
 * Other tabs are told when the sign-out is asked for, not when it is answered. Somebody who clicks
 * and walks away has left the other tabs to whoever comes next, and an answer can take as long as a
 * timeout, or never come. The message for that moment is `'sign-out-unconfirmed'`: nothing is
 * confirmed yet, and `'ended'` waits for the backend to say so.
 */
describe('when other tabs are told', () => {
  it('GivenASignOutNotYetAnswered_WhenClicked_ThenOtherTabsHaveForgottenTheirTokenAndThisTabAlreadyWarns', async () => {
    // A second copy of the session module, with its own channel, is a second tab in every way that
    // matters here: its own token, its own memory, reached only through the channel.
    vi.resetModules();
    const secondTab = await import('../api/session');
    secondTab.holdSession({
      accessToken: 'token-2',
      tokenType: 'Bearer',
      expiresIn: 600,
      session: A_TENANT,
    });
    await showThePeoplePageSignedIn(() => new Promise<Answer>(() => undefined));
    const heard = aListeningTab();

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() =>
      expect(heard, `what other tabs heard on ${CHANNEL}`).toContain(SIGN_OUT_UNCONFIRMED),
    );
    await waitFor(() =>
      expect(
        secondTab.currentAccessToken(),
        'the other tab should have forgotten its token',
      ).toBeNull(),
    );
    await expectTheWarning();
    expect(screen.queryByRole('button', { name: 'People' }), 'the shell is still shown').toBeNull();
    expect(currentAccessToken()).toBeNull();
  });
});

/**
 * A renewal that answers for somebody else means the cookie now belongs to an account that may be
 * signed in, legitimately, in another tab. This tab forgets its own session and tells nobody: an
 * `'ended'` would sign that other account out of every tab it is using.
 */
describe('a renewal answered with another account', () => {
  it('GivenAccountAHeld_WhenTheRenewalAnswersWithAccountB_ThenThisTabForgetsItAndPostsNothing', async () => {
    holdSession({ accessToken: 'token-a', tokenType: 'Bearer', expiresIn: 600, session: A_TENANT });
    routeTo({
      'GET /api/shifts': { status: 401, body: { detail: 'A valid access token is required.' } },
      'POST /api/auth/refresh': {
        status: 200,
        body: {
          accessToken: 'token-b',
          tokenType: 'Bearer',
          expiresIn: 600,
          session: ANOTHER_ACCOUNT,
        },
      },
    });
    const postedHere = recordWhatThisTabPosts();

    await api.get('/api/shifts').catch(() => undefined);

    expect(currentSession()).toBeNull();
    expect(postedHere(), `what this tab posted on ${CHANNEL}`).toEqual([]);
  });
});
