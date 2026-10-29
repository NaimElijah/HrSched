import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { A_TENANT, SignedIn } from '../test-support/session';
import { RulesPage } from './RulesPage';
import { WITHOUT_PAUSES } from '../test-support/typing';

/**
 * Revising the rules replaces the whole set — the backend does not merge — so the form must be
 * seeded from what is already configured. A blank form that posts on submit silently deletes every
 * rule the admin did not retype, and rosters that should be refused start publishing.
 */

const CONFIGURED = {
  version: 4,
  constraints: ['minimum rest between assignments', 'maximum daily working time'],
  minimumRestMinutes: 660,
  maximumDailyMinutes: 600,
  maximumWeeklyMinutes: null,
  maximumConsecutiveDays: null,
};

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

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('the rules page', () => {
  it('GivenConfiguredRules_WhenOpened_ThenTheFormShowsTheirCurrentValues', async () => {
    routeTo(CONFIGURED);
    render(
      <SignedIn session={A_TENANT}>
        <RulesPage />
      </SignedIn>,
    );

    const rest = await screen.findByLabelText(/Minimum rest/);
    await waitFor(() => expect(rest).toHaveValue(660));
    expect(screen.getByLabelText(/Maximum daily/)).toHaveValue(600);
    // Not configured stays blank; a suggested number here would be a threshold we invented.
    expect(screen.getByLabelText(/Maximum consecutive/)).toHaveValue(null);
  });

  it('GivenOneRuleChanged_WhenRevising_ThenTheOtherConfiguredRulesAreResent', async () => {
    const stub = routeTo(CONFIGURED);
    render(
      <SignedIn session={A_TENANT}>
        <RulesPage />
      </SignedIn>,
    );
    const daily = await screen.findByLabelText(/Maximum daily/);
    await waitFor(() => expect(daily).toHaveValue(600));

    await userEvent.clear(daily);
    await userEvent.type(daily, '480', WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Revise rules' }));

    await waitFor(() => expect(sentBody(stub).maximumDailyMinutes).toBe(480));
    // The rule nobody touched must survive: revising replaces the set wholesale.
    expect(sentBody(stub).minimumRestMinutes).toBe(660);
  });

  it('GivenAClearedField_WhenRevising_ThenThatRuleIsSwitchedOff', async () => {
    const stub = routeTo(CONFIGURED);
    render(
      <SignedIn session={A_TENANT}>
        <RulesPage />
      </SignedIn>,
    );
    const rest = await screen.findByLabelText(/Minimum rest/);
    await waitFor(() => expect(rest).toHaveValue(660));

    await userEvent.clear(rest);
    await userEvent.click(screen.getByRole('button', { name: 'Revise rules' }));

    await waitFor(() => expect(sentBody(stub)).not.toHaveProperty('minimumRestMinutes'));
    expect(sentBody(stub).maximumDailyMinutes).toBe(600);
  });
});
