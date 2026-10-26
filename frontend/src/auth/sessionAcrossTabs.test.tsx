import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { currentAccessToken, currentSession, endSession, holdSession } from '../api/session';
import { routeTo } from '../test-support/routeTo';
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
 * The employee is invented.
 */

const CHANNEL = 'hrsched.session';
const ENDED = 'ended';

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

/** A tab signed in as an Admin, on the People page, showing an employee's name. */
async function showThePeoplePageSignedIn() {
  routeTo({
    'POST /api/auth/refresh': {
      status: 200,
      body: { accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 600, session: A_TENANT },
    },
    'GET /api/auth/session': { status: 200, body: A_TENANT },
    'GET /api/employees': { status: 200, body: [AN_EMPLOYEE] },
    'GET /api/qualifications': { status: 200, body: [] },
  });
  render(
    <SessionProvider>
      <App />
    </SessionProvider>,
  );
  await screen.findByText(AN_EMPLOYEE.name);
}

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
