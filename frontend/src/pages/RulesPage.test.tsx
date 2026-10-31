import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  callsTo,
  holdingEverything,
  holdingWrites,
  routeTo,
  writes,
} from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
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
    // A refusal reads nothing back, so a guard still held here would leave the form dead.
    expect(screen.getByRole('button', { name: 'Revise rules' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    );
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
    // Unavailable, and still focusable and so able to say why (#257, C5).
    const revise = screen.getByRole('button', { name: 'Revise rules' });
    expect(revise).toHaveAttribute('aria-disabled', 'true');
    expect(revise).toHaveAccessibleDescription(/did not load/);
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

/**
 * What a keyboard or screen reader user is told about the form (#257). Every limit is the invented
 * one above.
 */
describe('the rules form, told rather than shown', () => {
  function show() {
    render(
      <SignedIn session={A_TENANT}>
        <RulesPage />
      </SignedIn>,
    );
  }

  /** A bare disabled button cannot be reached to learn why it does nothing (C5). */
  it('GivenTheStoredRulesNotYetLoaded_WhenShown_ThenReviseIsUnavailableAndSaysItIsLoading', async () => {
    const answer = holdingEverything(configured());
    show();
    const revise = screen.getByRole('button', { name: 'Revise rules' });

    expect(revise).toHaveAttribute('aria-disabled', 'true');
    // jsdom keeps focus on a disabled button, so this, not toHaveFocus(), is what C1 rests on.
    expect(revise).not.toBeDisabled();
    expect(revise).toHaveAccessibleDescription(/waits until/);
    answer();
    await waitFor(() => expect(screen.getByLabelText(/Minimum rest/)).toHaveValue(REST));
    expect(revise).not.toHaveAttribute('aria-disabled', 'true');
  });

  it('GivenTheStoredRulesNotYetLoaded_WhenReviseIsPressed_ThenNothingIsSent', async () => {
    const routed = configured();
    const answer = holdingEverything(routed);
    show();

    await userEvent.click(screen.getByRole('button', { name: 'Revise rules' }));

    answer();
    await waitFor(() => expect(screen.getByLabelText(/Minimum rest/)).toHaveValue(REST));
    expect(writes(routed)).toHaveLength(0);
  });

  /** A load that failed is otherwise a dead end: the form can never be sent (C5). */
  it('GivenTheStoredRulesFailedToLoad_WhenTryAgainIsPressed_ThenTheyAreAskedForAgain', async () => {
    const server: Record<string, Answer> = {
      '/api/constraints': { status: 500, body: { detail: 'Something went wrong.' } },
      'PUT /api/constraints': { status: 200, body: { ...CONFIGURED, version: 5 } },
    };
    const routed = routeTo(server);
    show();
    const tryAgain = await screen.findByRole('button', { name: /^Try again/ });
    const askedBefore = callsTo(routed, 'GET', '/api/constraints').length;
    server['/api/constraints'] = { status: 200, body: CONFIGURED };

    await userEvent.click(tryAgain);

    await waitFor(() => expect(screen.getByLabelText(/Minimum rest/)).toHaveValue(REST));
    expect(callsTo(routed, 'GET', '/api/constraints').length).toBeGreaterThan(askedBefore);
    expect(screen.getByRole('button', { name: 'Revise rules' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  /** Focus is on the heading by then, so the form turning usable is otherwise silent (C3). */
  it('GivenTheStoredRulesFailedToLoad_WhenTryAgainSucceeds_ThenAStatusSaysTheyLoaded', async () => {
    const server: Record<string, Answer> = {
      '/api/constraints': { status: 500, body: { detail: 'Something went wrong.' } },
    };
    routeTo(server);
    show();
    const tryAgain = await screen.findByRole('button', { name: /^Try again/ });
    const statuses = screen.getAllByRole('status');
    server['/api/constraints'] = { status: 200, body: CONFIGURED };

    await userEvent.click(tryAgain);

    await waitFor(() =>
      expect(statuses.map((status) => status.textContent)).toContain('Stored rules loaded.'),
    );
  });

  /**
   * A disabled button drops the keyboard focus to the page in a browser (C1), and a second
   * revision sent meanwhile would be made from a copy the first has already replaced.
   */
  it('GivenARevisionInFlight_WhenPressedAgain_ThenTheButtonKeepsFocusAndOnlyOneIsSent', async () => {
    const routed = configured();
    show();
    await waitFor(() => expect(screen.getByLabelText(/Minimum rest/)).toHaveValue(REST));
    const release = holdingWrites(routed);
    const revise = screen.getByRole('button', { name: 'Revise rules' });

    await userEvent.click(revise);
    await userEvent.click(revise);

    expect(revise).toHaveAttribute('aria-disabled', 'true');
    expect(revise).not.toBeDisabled();
    expect(revise).toHaveFocus();
    release();
    await waitFor(() => expect(callsTo(routed, 'PUT', '/api/constraints')).toHaveLength(1));
    await waitFor(() => expect(revise).not.toHaveAttribute('aria-disabled', 'true'));
    expect(writes(routed)).toHaveLength(1);
  });

  /**
   * Until the read-back lands, the form still holds the copy the revision replaced. Pressed again
   * then, Revise would store the old values over the new ones, and the status would call it a
   * success; and the fields must not flash the replaced values meanwhile.
   */
  it('GivenARevisionStoredButNotYetReadBack_WhenPressedAgain_ThenOnlyOneIsSentAndTheEditStays', async () => {
    const server: Record<string, Answer> = {
      '/api/constraints': { status: 200, body: CONFIGURED },
      'PUT /api/constraints': { status: 200, body: { ...CONFIGURED, version: 5 } },
    };
    const routed = routeTo(server);
    let readBack!: () => void;
    const readingBack = new Promise<void>((resolve) => {
      readBack = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((path: string, init?: RequestInit) =>
        (init?.method ?? 'GET') === 'GET' && callsTo(routed, 'PUT', '/api/constraints').length > 0
          ? readingBack.then(() => routed(path, init))
          : routed(path, init),
      ),
    );
    show();
    const daily = screen.getByLabelText(/Maximum daily/);
    await waitFor(() => expect(daily).toHaveValue(DAILY));
    await userEvent.clear(daily);
    await userEvent.type(daily, String(REVISED_DAILY), WITHOUT_PAUSES);
    const revise = screen.getByRole('button', { name: 'Revise rules' });

    await userEvent.click(revise);
    await screen.findByText(/rules revised/i);
    await userEvent.click(revise);

    expect(revise).toHaveAttribute('aria-disabled', 'true');
    expect(daily).toHaveValue(REVISED_DAILY);
    server['/api/constraints'] = {
      status: 200,
      body: { ...CONFIGURED, version: 5, maximumDailyMinutes: REVISED_DAILY },
    };
    readBack();
    await waitFor(() => expect(revise).not.toHaveAttribute('aria-disabled', 'true'));
    expect(daily).toHaveValue(REVISED_DAILY);
    expect(callsTo(routed, 'PUT', '/api/constraints')).toHaveLength(1);
  });

  /** The fields look the same before and after, so without a word nothing says it was stored (C3). */
  it('GivenARevision_WhenItSucceeds_ThenAStatusSaysTheRulesWereRevised', async () => {
    configured();
    show();
    await waitFor(() => expect(screen.getByLabelText(/Minimum rest/)).toHaveValue(REST));
    // Captured before the revision: a live region that appears along with its text is not read.
    const statuses = screen.getAllByRole('status');

    await userEvent.click(screen.getByRole('button', { name: 'Revise rules' }));

    await waitFor(() =>
      expect(statuses.map((status) => status.textContent)).toContainEqual(
        expect.stringMatching(/rules revised: now version 5/i),
      ),
    );
  });

  /** A success left on screen while the next revision is typed reads as that one's (C9). */
  it('GivenARevisionSucceeded_WhenTheFormIsEditedAgain_ThenTheSuccessIsNoLongerSaid', async () => {
    configured();
    show();
    await waitFor(() => expect(screen.getByLabelText(/Minimum rest/)).toHaveValue(REST));
    await userEvent.click(screen.getByRole('button', { name: 'Revise rules' }));
    await screen.findByText(/rules revised/i);

    await userEvent.type(screen.getByLabelText(/Minimum rest/), '1', WITHOUT_PAUSES);

    expect(screen.queryByText(/rules revised/i)).not.toBeInTheDocument();
  });

  /** Try again goes once pressed, and focus left on a button that is gone falls to the page (C2). */
  it('GivenTheStoredRulesFailedToLoad_WhenTryAgainIsPressed_ThenTheSectionHeadingHasFocus', async () => {
    routeTo({ '/api/constraints': { status: 500, body: { detail: 'Something went wrong.' } } });
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^Try again/ }));

    expect(screen.getByRole('heading', { name: 'Working-time rules' })).toHaveFocus();
  });

  /** Read in order, Try again comes after what it answers, not before it. */
  it('GivenTheStoredRulesFailedToLoad_WhenShown_ThenTheFailureIsReadBeforeTryAgain', async () => {
    routeTo({ '/api/constraints': { status: 500, body: { detail: 'Something went wrong.' } } });
    show();

    const tryAgain = await screen.findByRole('button', { name: /^Try again/ });

    expect(
      screen.getByRole('alert').compareDocumentPosition(tryAgain) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });
});
