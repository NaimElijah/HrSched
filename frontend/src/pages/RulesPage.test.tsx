import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { callsTo, routeTo, writes } from '../test-support/routeTo';
import { A_SCHEDULER, A_TENANT, SignedIn } from '../test-support/session';
import { RulesPage } from './RulesPage';
import { WITHOUT_PAUSES } from '../test-support/typing';

/**
 * Revising the rules replaces the whole set — the backend does not merge — so the form must be
 * seeded from what is already configured. A blank form that posts on submit silently deletes every
 * rule the admin did not retype, and rosters that should be refused start publishing.
 *
 * Every limit here is invented, and odd on purpose so that it matches no statute: a rule's number
 * is configuration, never something this code base writes down.
 */

/** Invented minutes, the configured minimum rest. */
const REST = 661;
/** Invented minutes, the configured daily maximum. */
const DAILY = 601;
/** Invented minutes, the daily maximum typed in its place. */
const REVISED_DAILY = 481;

const CONFIGURED = {
  version: 4,
  constraints: ['minimum rest between assignments', 'maximum daily working time'],
  minimumRestMinutes: REST,
  maximumDailyMinutes: DAILY,
  maximumWeeklyMinutes: null,
  maximumConsecutiveDays: null,
};

/** Answers the current rules with what is configured, and accepts a revision. */
function configured() {
  return routeTo({
    '/api/constraints': { status: 200, body: CONFIGURED },
    'PUT /api/constraints': { status: 200, body: { ...CONFIGURED, version: 5 } },
  });
}

function sentBody(stub: ReturnType<typeof routeTo>): Record<string, unknown> {
  const body = callsTo(stub, 'PUT', '/api/constraints')[0]?.[1]?.body;
  if (typeof body !== 'string') {
    throw new Error('no revision was sent to PUT /api/constraints');
  }
  return JSON.parse(body) as Record<string, unknown>;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the rules page', () => {
  it('GivenConfiguredRules_WhenOpened_ThenTheFormShowsTheirCurrentValues', async () => {
    configured();
    render(
      <SignedIn session={A_TENANT}>
        <RulesPage />
      </SignedIn>,
    );

    const rest = await screen.findByLabelText(/Minimum rest/);
    await waitFor(() => expect(rest).toHaveValue(REST));
    expect(screen.getByLabelText(/Maximum daily/)).toHaveValue(DAILY);
    // Not configured stays blank; a suggested number here would be a threshold we invented.
    expect(screen.getByLabelText(/Maximum consecutive/)).toHaveValue(null);
  });

  it('GivenOneRuleChanged_WhenRevising_ThenTheOtherConfiguredRulesAreResent', async () => {
    const stub = configured();
    render(
      <SignedIn session={A_TENANT}>
        <RulesPage />
      </SignedIn>,
    );
    const daily = await screen.findByLabelText(/Maximum daily/);
    await waitFor(() => expect(daily).toHaveValue(DAILY));

    await userEvent.clear(daily);
    await userEvent.type(daily, String(REVISED_DAILY), WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Revise rules' }));

    await waitFor(() => expect(sentBody(stub).maximumDailyMinutes).toBe(REVISED_DAILY));
    // The rule nobody touched must survive: revising replaces the set wholesale. And nothing else
    // is sent: the backend refuses a member the request does not take (ADR-058).
    expect(sentBody(stub)).toEqual({
      minimumRestMinutes: REST,
      maximumDailyMinutes: REVISED_DAILY,
    });
  });

  /** The backend's own words, and the value typed stays to be corrected rather than retyped. */
  it('GivenARevisionTheBackendRefuses_WhenRevising_ThenItsReasonIsShownAndTheEditKept', async () => {
    routeTo({
      '/api/constraints': { status: 200, body: CONFIGURED },
      // Revising the rules is an Admin's. The field's own `min` stops a non-positive limit before
      // the backend could refuse it as invalid, so the refusal that can reach this form is the role's.
      'PUT /api/constraints': { status: 403, body: { detail: 'Your role does not permit this.' } },
    });
    render(
      <SignedIn session={A_SCHEDULER}>
        <RulesPage />
      </SignedIn>,
    );
    const daily = await screen.findByLabelText(/Maximum daily/);
    await waitFor(() => expect(daily).toHaveValue(DAILY));

    await userEvent.clear(daily);
    await userEvent.type(daily, String(REVISED_DAILY), WITHOUT_PAUSES);
    await userEvent.click(screen.getByRole('button', { name: 'Revise rules' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Your role does not permit this.');
    expect(daily).toHaveValue(REVISED_DAILY);
  });

  /**
   * Until the stored rules have loaded, every field is blank and reads "not enforced" while nothing
   * is known. A revision sent then would carry only what was typed and switch every other rule off,
   * so there is nothing to send until they have (#239).
   */
  it('GivenTheStoredRulesFailedToLoad_WhenTryingToRevise_ThenNothingIsSent', async () => {
    const stub = routeTo({
      '/api/constraints': { status: 500, body: { detail: 'Something went wrong.' } },
      'PUT /api/constraints': { status: 200, body: CONFIGURED },
    });
    render(
      <SignedIn session={A_TENANT}>
        <RulesPage />
      </SignedIn>,
    );
    await screen.findByRole('alert');

    await userEvent.type(
      screen.getByLabelText(/Maximum daily/),
      String(REVISED_DAILY),
      WITHOUT_PAUSES,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Revise rules' }));

    expect(writes(stub)).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Revise rules' })).toBeDisabled();
  });

  it('GivenAClearedField_WhenRevising_ThenThatRuleIsSwitchedOff', async () => {
    const stub = configured();
    render(
      <SignedIn session={A_TENANT}>
        <RulesPage />
      </SignedIn>,
    );
    const rest = await screen.findByLabelText(/Minimum rest/);
    await waitFor(() => expect(rest).toHaveValue(REST));

    await userEvent.clear(rest);
    await userEvent.click(screen.getByRole('button', { name: 'Revise rules' }));

    await waitFor(() => expect(sentBody(stub)).not.toHaveProperty('minimumRestMinutes'));
    expect(sentBody(stub).maximumDailyMinutes).toBe(DAILY);
  });
});
