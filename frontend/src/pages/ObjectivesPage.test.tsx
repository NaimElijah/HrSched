import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { A_TENANT, SignedIn } from '../test-support/session';
import { ObjectivesPage } from './ObjectivesPage';

/**
 * Weighing the objectives (F35, ADR-010). Revising replaces the weights whole, so the form is
 * seeded from what is stored: a blank form posting on submit would unweigh every objective the
 * admin did not retype.
 *
 * Every weight here is invented.
 */

const STORED = { fairness: 3, preferences: null, disruption: 1 };

function routeTo(body: unknown) {
  const stub = vi.fn((_path: string, init?: RequestInit) => {
    void _path;
    void init;
    return Promise.resolve({
      ok: true,
      status: 200,
      headers: new Headers(),
      text: () => Promise.resolve(JSON.stringify(body)),
    });
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

function sentBody(stub: ReturnType<typeof routeTo>): Record<string, unknown> {
  const put = stub.mock.calls.find((call) => call[1]?.method === 'PUT');
  const body = put?.[1]?.body;
  if (typeof body !== 'string') {
    throw new Error('no PUT was sent');
  }
  return JSON.parse(body) as Record<string, unknown>;
}

function show() {
  render(
    <SignedIn session={A_TENANT}>
      <ObjectivesPage />
    </SignedIn>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('the objectives page', () => {
  it('GivenStoredWeights_WhenOpened_ThenTheFormShowsThem', async () => {
    routeTo(STORED);
    show();

    const fairness = await screen.findByLabelText(/Fairness weight/);
    await waitFor(() => expect(fairness).toHaveValue(3));
    expect(screen.getByLabelText(/Disruption weight/)).toHaveValue(1);
    // Not weighted stays blank; a suggested weight here would be one we chose for them.
    expect(screen.getByLabelText(/Preferences weight/)).toHaveValue(null);
  });

  it('GivenNothingWeighted_WhenOpened_ThenNoWeightIsSuggested', async () => {
    const stub = routeTo({ fairness: null, preferences: null, disruption: null });
    show();
    await waitFor(() => expect(stub).toHaveBeenCalled());

    for (const label of [/Fairness weight/, /Preferences weight/, /Disruption weight/]) {
      expect(screen.getByLabelText(label)).toHaveValue(null);
    }
  });

  it('GivenOneWeightChanged_WhenRevising_ThenTheOtherStoredWeightsAreResent', async () => {
    const stub = routeTo(STORED);
    show();
    const preferences = await screen.findByLabelText(/Preferences weight/);
    await waitFor(() => expect(screen.getByLabelText(/Fairness weight/)).toHaveValue(3));

    await userEvent.type(preferences, '2');
    await userEvent.click(screen.getByRole('button', { name: 'Weigh objectives' }));

    await waitFor(() => expect(sentBody(stub).preferences).toBe(2));
    expect(sentBody(stub).fairness).toBe(3);
    expect(sentBody(stub).disruption).toBe(1);
  });

  it('GivenAClearedWeight_WhenRevising_ThenThatObjectiveIsNoLongerWeighed', async () => {
    const stub = routeTo(STORED);
    show();
    const fairness = await screen.findByLabelText(/Fairness weight/);
    await waitFor(() => expect(fairness).toHaveValue(3));

    await userEvent.clear(fairness);
    await userEvent.click(screen.getByRole('button', { name: 'Weigh objectives' }));

    await waitFor(() => expect(sentBody(stub)).not.toHaveProperty('fairness'));
    expect(sentBody(stub).disruption).toBe(1);
  });
});
