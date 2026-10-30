import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { callsTo, routeTo, writes } from '../test-support/routeTo';
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
    // Enabled only once the stored weights have loaded: before that every field is blank anyway.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Weigh objectives' })).toBeEnabled(),
    );

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
    expect(sentBody(stub).fairness).toBe(3);
    expect(sentBody(stub).disruption).toBe(1);
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
