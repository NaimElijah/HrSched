import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { A_TENANT, SignedIn } from '../test-support/session';
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
    routeTo({ fairness: null, preferences: null, disruption: null });
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
    const stub = routeTo(STORED);
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
    const stub = vi.fn((_path: string, init?: RequestInit) => {
      void _path;
      void init;
      return Promise.resolve({
        ok: false,
        status: 500,
        headers: new Headers(),
        text: () => Promise.resolve(JSON.stringify({ detail: 'Something went wrong.' })),
      });
    });
    vi.stubGlobal('fetch', stub);
    show();
    await screen.findByRole('alert');

    await userEvent.type(screen.getByLabelText(/Fairness weight/), '4', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Weigh objectives' }));

    expect(stub.mock.calls.some((call) => call[1]?.method === 'PUT')).toBe(false);
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
