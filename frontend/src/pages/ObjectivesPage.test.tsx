import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { callsTo, holdingWrites, routeTo, writes } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
import type { Session } from '../api/session';
import { A_SCHEDULER, A_TENANT, SignedIn } from '../test-support/session';
import { ObjectivesPage } from './ObjectivesPage';
import { WITHOUT_PAUSES } from '../test-support/typing';

/**
 * Weighing the objectives (F35, ADR-010). Revising replaces the weights whole, so the form is
 * seeded from what is stored: a blank form posting on submit would unweigh every objective the
 * admin did not retype.
 *
 * Every weight here is invented.
 */

const STORED = { fairness: 3, preferences: null, disruption: 1 };

/** Answers the stored weights with these, and accepts a revision. */
function weighing(stored: unknown) {
  return routeTo({
    '/api/objectives': { status: 200, body: stored },
    'PUT /api/objectives': { status: 200, body: stored },
  });
}

function sentBody(stub: ReturnType<typeof routeTo>): Record<string, unknown> {
  const body = callsTo(stub, 'PUT', '/api/objectives')[0]?.[1]?.body;
  if (typeof body !== 'string') {
    throw new Error('no revision was sent to PUT /api/objectives');
  }
  return JSON.parse(body) as Record<string, unknown>;
}

function show(session: Session = A_TENANT) {
  render(
    <SignedIn session={session}>
      <ObjectivesPage />
    </SignedIn>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the objectives page', () => {
  it('GivenStoredWeights_WhenOpened_ThenTheFormShowsThem', async () => {
    weighing(STORED);
    show();

    const fairness = await screen.findByLabelText(/Fairness weight/);
    await waitFor(() => expect(fairness).toHaveValue(3));
    expect(screen.getByLabelText(/Disruption weight/)).toHaveValue(1);
    // Not weighted stays blank; a suggested weight here would be one we chose for them.
    expect(screen.getByLabelText(/Preferences weight/)).toHaveValue(null);
  });

  it('GivenNothingWeighted_WhenOpened_ThenNoWeightIsSuggested', async () => {
    weighing({ fairness: null, preferences: null, disruption: null });
    show();
    // Only once the stored weights have loaded: before that every field is blank anyway. Waited for
    // through the busy region, since the button stays enabled and only says it is unavailable (#257).
    await waitFor(() => expect(screen.queryByText('Working…')).not.toBeInTheDocument());

    for (const label of [/Fairness weight/, /Preferences weight/, /Disruption weight/]) {
      expect(screen.getByLabelText(label)).toHaveValue(null);
    }
  });

  it('GivenOneWeightChanged_WhenRevising_ThenTheOtherStoredWeightsAreResent', async () => {
    const stub = weighing(STORED);
    show();
    const preferences = await screen.findByLabelText(/Preferences weight/);
    await waitFor(() => expect(screen.getByLabelText(/Fairness weight/)).toHaveValue(3));

    await userEvent.type(preferences, '2', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Weigh objectives' }));

    await waitFor(() => expect(sentBody(stub).preferences).toBe(2));
    // Exactly the three weights: the backend refuses a member the request does not take (ADR-058).
    expect(sentBody(stub)).toEqual({ fairness: 3, preferences: 2, disruption: 1 });
  });

  /**
   * Weights are not versioned, so a revision sent without the stored ones would unweigh them for
   * good. Until they have loaded, the form has nothing to resend, and so it sends nothing.
   */
  it('GivenTheStoredWeightsFailedToLoad_WhenTryingToRevise_ThenNothingIsSent', async () => {
    const stub = routeTo({
      '/api/objectives': { status: 500, body: { detail: 'Something went wrong.' } },
      'PUT /api/objectives': { status: 200, body: STORED },
    });
    show();
    await screen.findByRole('alert');

    await userEvent.type(screen.getByLabelText(/Fairness weight/), '4', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Weigh objectives' }));

    expect(writes(stub)).toHaveLength(0);
  });

  it('GivenARevisionTheBackendRefuses_WhenRevising_ThenItsReasonIsShownAndTheEditKept', async () => {
    routeTo({
      '/api/objectives': { status: 200, body: STORED },
      // Revising the weights is an Admin's, so a Scheduler is who the backend refuses.
      'PUT /api/objectives': { status: 403, body: { detail: 'Your role does not permit this.' } },
    });
    show(A_SCHEDULER);
    const preferences = await screen.findByLabelText(/Preferences weight/);
    await waitFor(() => expect(screen.getByLabelText(/Fairness weight/)).toHaveValue(3));

    await userEvent.type(preferences, '2', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Weigh objectives' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Your role does not permit this.');
    expect(preferences).toHaveValue(2);
  });

  it('GivenAClearedWeight_WhenRevising_ThenThatObjectiveIsNoLongerWeighed', async () => {
    const stub = weighing(STORED);
    show();
    const fairness = await screen.findByLabelText(/Fairness weight/);
    await waitFor(() => expect(fairness).toHaveValue(3));

    await userEvent.clear(fairness);
    await userEvent.click(screen.getByRole('button', { name: 'Weigh objectives' }));

    await waitFor(() => expect(sentBody(stub)).not.toHaveProperty('fairness'));
    expect(sentBody(stub).disruption).toBe(1);
  });
});

/** What a keyboard or screen reader user is told about the form (#257). Every weight is invented. */
describe('the objectives form, told rather than shown', () => {
  /** Answers every request only once the returned function is called. */
  function holdingEverything(routed: ReturnType<typeof routeTo>): () => void {
    let answer!: () => void;
    const answering = new Promise<void>((resolve) => {
      answer = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((path: string, init?: RequestInit) => answering.then(() => routed(path, init))),
    );
    return answer;
  }

  /** A bare disabled button cannot be reached to learn why it does nothing (C5). */
  it('GivenTheStoredWeightsNotYetLoaded_WhenShown_ThenWeighIsUnavailableAndSaysItIsLoading', async () => {
    const answer = holdingEverything(weighing(STORED));
    show();
    const weigh = screen.getByRole('button', { name: 'Weigh objectives' });

    expect(weigh).toHaveAttribute('aria-disabled', 'true');
    expect(weigh).not.toBeDisabled();
    expect(weigh).toHaveAccessibleDescription(/load/i);
    answer();
    await waitFor(() => expect(screen.getByLabelText(/Fairness weight/)).toHaveValue(3));
    expect(weigh).not.toHaveAttribute('aria-disabled', 'true');
  });

  it('GivenTheStoredWeightsNotYetLoaded_WhenWeighIsPressed_ThenNothingIsSent', async () => {
    const routed = weighing(STORED);
    const answer = holdingEverything(routed);
    show();

    await userEvent.click(screen.getByRole('button', { name: 'Weigh objectives' }));

    answer();
    await waitFor(() => expect(screen.getByLabelText(/Fairness weight/)).toHaveValue(3));
    expect(writes(routed)).toHaveLength(0);
  });

  /** A load that failed is otherwise a dead end: the weights can never be sent (C5). */
  it('GivenTheStoredWeightsFailedToLoad_WhenTryAgainIsPressed_ThenTheyAreAskedForAgain', async () => {
    const server: Record<string, Answer> = {
      '/api/objectives': { status: 500, body: { detail: 'Something went wrong.' } },
      'PUT /api/objectives': { status: 200, body: STORED },
    };
    const routed = routeTo(server);
    show();
    const tryAgain = await screen.findByRole('button', { name: /^Try again/ });
    const askedBefore = callsTo(routed, 'GET', '/api/objectives').length;
    server['/api/objectives'] = { status: 200, body: STORED };

    await userEvent.click(tryAgain);

    await waitFor(() => expect(screen.getByLabelText(/Fairness weight/)).toHaveValue(3));
    expect(callsTo(routed, 'GET', '/api/objectives').length).toBeGreaterThan(askedBefore);
    expect(screen.getByRole('button', { name: 'Weigh objectives' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  /**
   * A disabled button drops the keyboard focus to the page in a browser (C1), and a second
   * revision sent meanwhile would be one nobody asked for.
   */
  it('GivenAWeighingInFlight_WhenPressedAgain_ThenTheButtonKeepsFocusAndOnlyOneIsSent', async () => {
    const routed = weighing(STORED);
    show();
    await waitFor(() => expect(screen.getByLabelText(/Fairness weight/)).toHaveValue(3));
    const release = holdingWrites(routed);
    const weigh = screen.getByRole('button', { name: 'Weigh objectives' });

    await userEvent.click(weigh);
    await userEvent.click(weigh);

    expect(weigh).toHaveAttribute('aria-disabled', 'true');
    expect(weigh).not.toBeDisabled();
    expect(weigh).toHaveFocus();
    release();
    await waitFor(() => expect(callsTo(routed, 'PUT', '/api/objectives')).toHaveLength(1));
    await waitFor(() => expect(weigh).not.toHaveAttribute('aria-disabled', 'true'));
    expect(writes(routed)).toHaveLength(1);
  });

  /** The fields look the same before and after, so without a word nothing says it was stored (C3). */
  it('GivenAWeighing_WhenItSucceeds_ThenAStatusSaysTheObjectivesWereWeighed', async () => {
    weighing(STORED);
    show();
    await waitFor(() => expect(screen.getByLabelText(/Fairness weight/)).toHaveValue(3));

    await userEvent.click(screen.getByRole('button', { name: 'Weigh objectives' }));

    await waitFor(() =>
      expect(screen.getAllByRole('status').map((status) => status.textContent)).toContainEqual(
        expect.stringMatching(/objectives weighed/i),
      ),
    );
  });

  /** A success left on screen while the next weighing is typed reads as that one's (C9). */
  it('GivenAWeighingSucceeded_WhenTheFormIsEditedAgain_ThenTheSuccessIsNoLongerSaid', async () => {
    weighing(STORED);
    show();
    await waitFor(() => expect(screen.getByLabelText(/Fairness weight/)).toHaveValue(3));
    await userEvent.click(screen.getByRole('button', { name: 'Weigh objectives' }));
    await screen.findByText(/objectives weighed/i);

    await userEvent.type(screen.getByLabelText(/Fairness weight/), '1', WITHOUT_PAUSES);

    expect(screen.queryByText(/objectives weighed/i)).not.toBeInTheDocument();
  });

  /** Try again goes once pressed, and focus left on a button that is gone falls to the page (C2). */
  it('GivenTheStoredWeightsFailedToLoad_WhenTryAgainIsPressed_ThenTheSectionHeadingHasFocus', async () => {
    routeTo({ '/api/objectives': { status: 500, body: { detail: 'Something went wrong.' } } });
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^Try again/ }));

    expect(screen.getByRole('heading', { name: 'What makes a roster better' })).toHaveFocus();
  });
});
