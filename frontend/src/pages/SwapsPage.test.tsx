import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Employee } from '../api/workforce';
import { dayLabel, timeLabel } from '../components/localDates';
import { A_TENANT, SignedIn } from '../test-support/session';
import { callsTo, routeTo, type Answer } from '../test-support/routeTo';
import { SwapsPage } from './SwapsPage';

/**
 * Where a Scheduler answers swap requests (F7).
 *
 * Approving publishes a replacement roster, so it can be refused exactly as publishing can — every
 * broken rule named, and no override. The decision buttons are offered whatever the role: whether
 * this role may decide is the backend's to say, and its 403 is what the page shows.
 */

const ADA: Employee = {
  id: 'emp-1',
  name: 'Ada',
  qualifications: [],
  unavailability: [],
  weeklyContractedMinutes: null,
};

const GRACE: Employee = { ...ADA, id: 'emp-2', name: 'Grace' };

const SHIFT = {
  id: 'shift-1',
  start: '2026-10-06T08:00:00Z',
  end: '2026-10-06T16:00:00Z',
  zone: 'UTC',
  requiredHeadcount: 1,
  requiredQualifications: [],
};

const PENDING = {
  id: 'swap-1',
  roster: 'roster-1',
  shift: 'shift-1',
  requester: 'emp-1',
  counterparty: 'emp-2',
  status: 'REQUESTED',
};

const WHEN = `${timeLabel(SHIFT.start)} to ${timeLabel(SHIFT.end)} on ${dayLabel(SHIFT.start)}`;

/** One pending request, from Ada, for Grace to take her shift. */
function aQueue(overrides: Record<string, Answer> = {}) {
  return routeTo({
    '/api/swaps': { status: 200, body: [PENDING] },
    '/api/shifts/shift-1': { status: 200, body: SHIFT },
    ...overrides,
  });
}

function show(people: readonly Employee[] = [ADA, GRACE]) {
  render(
    <SignedIn session={A_TENANT}>
      <SwapsPage people={people} />
    </SignedIn>,
  );
}

async function rowOfTheShift(): Promise<HTMLElement> {
  const cell = await screen.findByText(WHEN);
  const row = cell.closest('tr');
  if (row === null) {
    throw new Error('the shift is not in a table row');
  }
  return row;
}

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('the queue', () => {
  it('GivenPendingSwaps_WhenOpened_ThenEachIsListedWithNamesAndShiftTime', async () => {
    aQueue();

    show();

    const row = await rowOfTheShift();
    expect(within(row).getByText('Ada')).toBeInTheDocument();
    expect(within(row).getByText('Grace')).toBeInTheDocument();
    expect(within(row).getByText('waiting on a Scheduler')).toBeInTheDocument();
  });

  it('GivenNobodyCanBeNamed_WhenListed_ThenIdentifiersAreShownRatherThanBlanks', async () => {
    aQueue();

    show([]);

    const row = await rowOfTheShift();
    expect(within(row).getByText('emp-1')).toBeInTheDocument();
    expect(within(row).getByText('emp-2')).toBeInTheDocument();
  });

  /** Every shift was read in one all-or-nothing batch, so one failure took the time off every row. */
  it('GivenOneShiftThatCannotBeRead_WhenListed_ThenOnlyItsRowLosesItsTime', async () => {
    aQueue({
      '/api/swaps': {
        status: 200,
        body: [PENDING, { ...PENDING, id: 'swap-2', shift: 'shift-gone' }],
      },
      '/api/shifts/shift-gone': { status: 404, body: { detail: 'No such resource.' } },
    });

    show();

    expect(await screen.findByText(WHEN)).toBeInTheDocument();
    expect(screen.getByText('A shift')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Nothing here for this organisation.');
  });

  it('GivenADecidedSwap_WhenListed_ThenItOffersNoDecision', async () => {
    aQueue({ '/api/swaps': { status: 200, body: [{ ...PENDING, status: 'APPROVED' }] } });

    show();

    const row = await rowOfTheShift();
    expect(within(row).getByText('approved')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Approve/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Decline/ })).not.toBeInTheDocument();
  });

  /**
   * R33: a request whose roster was replaced is over, though nobody decided it. It says so, and
   * offers nothing to decide, because the server would refuse either answer.
   */
  it('GivenALapsedSwap_WhenListed_ThenItSaysWhyAndOffersNoDecision', async () => {
    aQueue({ '/api/swaps': { status: 200, body: [{ ...PENDING, status: 'LAPSED' }] } });

    show();

    const row = await rowOfTheShift();
    expect(within(row).getByText('lapsed: its roster was replaced')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Approve/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Decline/ })).not.toBeInTheDocument();
  });

  it('GivenNoSwaps_WhenOpened_ThenItSaysNothingHasBeenAskedFor', async () => {
    aQueue({ '/api/swaps': { status: 200, body: [] } });

    show();

    expect(await screen.findByText('Nobody has asked for a swap.')).toBeInTheDocument();
  });
});

describe('deciding', () => {
  it('GivenAPendingSwap_WhenApproved_ThenItIsPostedAndTheListReloads', async () => {
    const stub = aQueue({
      'POST /api/swaps/swap-1/approval': {
        status: 200,
        body: { id: 'roster-2', status: 'PUBLISHED' },
      },
    });
    show();
    const approve = await screen.findByRole('button', { name: /^Approve/ });
    const listedBefore = callsTo(stub, 'GET', '/api/swaps').length;

    await userEvent.click(approve);

    await waitFor(() =>
      expect(callsTo(stub, 'POST', '/api/swaps/swap-1/approval')).toHaveLength(1),
    );
    await waitFor(() =>
      expect(callsTo(stub, 'GET', '/api/swaps').length).toBeGreaterThan(listedBefore),
    );
    expect(await screen.findByText(/^Approved\./)).toHaveAttribute('role', 'status');
  });

  /**
   * A second approval racing the first could publish a second replacement roster, so while one
   * decision is on its way no other can be sent.
   */
  it('GivenADecisionInFlight_WhenApproveIsPressedAgain_ThenOnlyOneApprovalIsSent', async () => {
    const routed = aQueue({
      'POST /api/swaps/swap-1/approval': {
        status: 200,
        body: { id: 'roster-2', status: 'PUBLISHED' },
      },
    });
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((path: string, init?: RequestInit) =>
        (init?.method ?? 'GET') === 'GET'
          ? routed(path, init)
          : held.then(() => routed(path, init)),
      ),
    );
    show();
    const approve = await screen.findByRole('button', { name: /^Approve/ });

    await userEvent.click(approve);
    await userEvent.click(approve);

    expect(approve).toBeDisabled();
    expect(screen.getByRole('button', { name: /^Decline/ })).toBeDisabled();
    release();
    await screen.findByText(/^Approved\./);
    expect(callsTo(routed, 'POST', '/api/swaps/swap-1/approval')).toHaveLength(1);
  });

  it('GivenAPendingSwap_WhenDeclined_ThenARejectionIsPosted', async () => {
    const stub = aQueue({ 'POST /api/swaps/swap-1/rejection': { status: 204, body: null } });
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^Decline/ }));

    await waitFor(() =>
      expect(callsTo(stub, 'POST', '/api/swaps/swap-1/rejection')).toHaveLength(1),
    );
    expect(await screen.findByText(/^Declined\./)).toHaveAttribute('role', 'status');
  });

  /** The reason approving is a publication: the swap cannot become a way around the rules. */
  it('GivenASwapBreakingARule_WhenApproving_ThenEveryViolationIsShownByName', async () => {
    aQueue({
      'POST /api/swaps/swap-1/approval': {
        status: 422,
        body: {
          detail: 'The roster breaks 1 working-time rule(s)',
          violations: [
            {
              employee: 'emp-2',
              constraint: 'maximum daily working time',
              detail: 'PT16H worked on 2026-10-06 exceeds the maximum of PT10H',
            },
          ],
        },
      },
    });
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^Approve/ }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Grace');
    expect(alert).toHaveTextContent('maximum daily working time');
    expect(alert).toHaveTextContent('There is no override');
  });

  it('GivenASwapAlreadyDecided_WhenApproving_ThenItSaysSomebodyAnsweredFirst', async () => {
    aQueue({
      'POST /api/swaps/swap-1/approval': {
        status: 409,
        body: { detail: 'That swap request has already been decided.' },
      },
    });
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^Approve/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That swap request has already been decided.',
    );
  });

  it('GivenAnEmployeeRole_WhenApproving_ThenTheRoleRefusalIsShown', async () => {
    aQueue({
      'POST /api/swaps/swap-1/approval': {
        status: 403,
        body: { detail: 'Your role does not permit this.' },
      },
    });
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^Approve/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Your role may not do this.');
  });
});
