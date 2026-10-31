import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Session } from '../api/session';
import type { Employee } from '../api/workforce';
import { dayLabel, timeLabel } from '../components/localDates';
import { A_TENANT, SignedIn } from '../test-support/session';
import { callsTo, holdingWrites, routeTo, writes, type Answer } from '../test-support/routeTo';
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

function show(people: readonly Employee[] = [ADA, GRACE], session: Session = A_TENANT) {
  render(
    <SignedIn session={session}>
      <SwapsPage people={people} />
    </SignedIn>,
  );
}

/** The row a shift's time heads, found by role: its row header names the shift. */
async function rowOfTheShift(when: string = WHEN): Promise<HTMLElement> {
  const header = await screen.findByRole('rowheader', { name: when });
  const row = screen.getAllByRole('row').find((candidate) => candidate.contains(header));
  if (row === undefined) {
    throw new Error('the shift is not in a table row');
  }
  return row;
}

/** The row right after the one a shift's time heads: where a refusal of that request belongs (C6). */
function rowAfterTheRowOf(table: HTMLElement, when: string): HTMLElement {
  const rows = within(table).getAllByRole('row');
  const index = rows.findIndex(
    (row) => within(row).queryByRole('rowheader', { name: when }) !== null,
  );
  const next = rows[index + 1];
  if (index < 0 || next === undefined) {
    throw new Error(`no row follows the row of ${when}`);
  }
  return next;
}

/**
 * C1: a control waiting for a request is marked so, and is not `disabled`, which would drop the
 * keyboard focus to the body. jsdom does not drop it either way, so the attribute is the proof.
 */
function expectWaiting(control: HTMLElement) {
  expect(control, 'aria-disabled while the request is in flight').toHaveAttribute(
    'aria-disabled',
    'true',
  );
  expect(control, 'not disabled, which would drop the keyboard focus').toBeEnabled();
}

/** A control that may act again: neither disabled nor marked as unable to act. */
function expectActionable(control: HTMLElement) {
  expect(control).toBeEnabled();
  expect(control, 'no longer aria-disabled').not.toHaveAttribute('aria-disabled', 'true');
}

afterEach(() => {
  vi.unstubAllGlobals();
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

  /**
   * A newer server may send a status this build has never heard of. It is named rather than left
   * blank, and nothing is offered to decide. The status is invented for the test.
   */
  it('GivenAStatusThisBuildDoesNotKnow_WhenListed_ThenItSaysSoByName', async () => {
    aQueue({ '/api/swaps': { status: 200, body: [{ ...PENDING, status: 'ESCALATED' }] } });

    show();

    const row = await rowOfTheShift();
    expect(within(row).getByText(/cannot describe \(ESCALATED\)/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Approve/ })).not.toBeInTheDocument();
  });

  it('GivenNoSwaps_WhenOpened_ThenItSaysNothingHasBeenAskedFor', async () => {
    aQueue({ '/api/swaps': { status: 200, body: [] } });

    show();

    expect(await screen.findByText('Nobody has asked for a swap.')).toBeInTheDocument();
  });

  /** A shift's time read under one account is not what the next account to sign in is shown. */
  it('GivenShiftsShown_WhenTheSessionChanges_ThenTheShiftsAreReadAgain', async () => {
    const stub = aQueue();
    const { rerender } = render(
      <SignedIn session={A_TENANT}>
        <SwapsPage people={[ADA, GRACE]} />
      </SignedIn>,
    );
    await rowOfTheShift();
    expect(callsTo(stub, 'GET', '/api/shifts/shift-1')).toHaveLength(1);

    rerender(
      <SignedIn session={{ ...A_TENANT, userId: '99999999-9999-4999-8999-999999999999' }}>
        <SwapsPage people={[ADA, GRACE]} />
      </SignedIn>,
    );

    await waitFor(() => expect(callsTo(stub, 'GET', '/api/shifts/shift-1')).toHaveLength(2));
    await rowOfTheShift();
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

    expectWaiting(approve);
    expect(approve, 'the pressed Approve keeps the keyboard focus').toHaveFocus();
    const decline = screen.getByRole('button', { name: /^Decline/ });
    expectWaiting(decline);
    // aria-disabled stops nothing by itself (C1): pressing the waiting Decline must send nothing.
    await userEvent.click(decline);
    release();
    await screen.findByText(/^Approved\./);
    expect(callsTo(routed, 'POST', '/api/swaps/swap-1/approval')).toHaveLength(1);
    expect(writes(routed), 'no decline was sent beside the approval').toHaveLength(1);
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
              // Invented, like every limit in a test: PT10H1M matches no statute.
              detail: 'PT16H worked on 2026-10-06 exceeds the maximum of PT10H1M',
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

  it('GivenASwapAlreadyDecided_WhenApproving_ThenTheBackendsReasonIsShown', async () => {
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

  it('GivenADeclineTheBackendRefuses_WhenDeclining_ThenTheRefusalIsShownRatherThanASuccess', async () => {
    aQueue({
      'POST /api/swaps/swap-1/rejection': {
        status: 409,
        body: { detail: 'That swap request has already been decided.' },
      },
    });
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^Decline/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That swap request has already been decided.',
    );
    expect(screen.queryByText(/^Declined\./)).not.toBeInTheDocument();
  });

  it('GivenAnEmployeeRole_WhenApproving_ThenTheRoleRefusalIsShown', async () => {
    aQueue({
      'POST /api/swaps/swap-1/approval': {
        status: 403,
        body: { detail: 'Your role does not permit this.' },
      },
    });
    show([ADA, GRACE], { ...A_TENANT, role: 'EMPLOYEE', employee: GRACE.id });

    await userEvent.click(await screen.findByRole('button', { name: /^Approve/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Your role may not do this.');
  });
});

// --- #257 (WCAG 2.2 AA): the queue as a keyboard and screen-reader user meets it --------------

describe('deciding, for a keyboard and screen-reader user', () => {
  it('GivenADeclineInFlight_WhenDeclineIsPressed_ThenBothAreAriaDisabledAndDeclineKeepsFocus', async () => {
    const release = holdingWrites(
      aQueue({ 'POST /api/swaps/swap-1/rejection': { status: 204, body: null } }),
    );
    show();
    const decline = await screen.findByRole('button', { name: /^Decline/ });

    await userEvent.click(decline);

    expectWaiting(decline);
    expect(decline, 'the pressed Decline keeps the keyboard focus').toHaveFocus();
    expectWaiting(screen.getByRole('button', { name: /^Approve/ }));
    release();
    await screen.findByText(/^Declined\./);
  });

  /**
   * C2: a decided swap offers nothing to decide, so its buttons go and focus moves to the header
   * cell of its row. The queue the server holds changes with the decision, as it really does.
   */
  it.each<[string, string, Answer, string]>([
    [
      'Approve',
      'approval',
      { status: 200, body: { id: 'roster-2', status: 'PUBLISHED' } },
      'APPROVED',
    ],
    ['Decline', 'rejection', { status: 204, body: null }, 'REJECTED'],
  ])(
    'GivenASwapDecided_WhenItsButtonsGo_ThenFocusIsOnThatRowsHeader (%s)',
    async (button, endpoint, answer, decided) => {
      const stub = routeTo({
        [`POST /api/swaps/swap-1/${endpoint}`]: answer,
        // A getter, so the queue is worked out on each request from the decisions received so far.
        get '/api/swaps'(): Answer {
          const answered = callsTo(stub, 'POST', `/api/swaps/swap-1/${endpoint}`).length > 0;
          return { status: 200, body: [answered ? { ...PENDING, status: decided } : PENDING] };
        },
        '/api/shifts/shift-1': { status: 200, body: SHIFT },
      });
      show();
      const row = await rowOfTheShift();

      await userEvent.click(within(row).getByRole('button', { name: new RegExp(`^${button}`) }));

      await waitFor(() =>
        expect(within(row).queryByRole('button', { name: /^Approve/ })).not.toBeInTheDocument(),
      );
      await waitFor(() => expect(within(row).getByRole('rowheader')).toHaveFocus());
    },
  );

  /** C6: the refusal is of one row's decision, so it is shown at that row, not above the table. */
  it('GivenAnApprovalRefused_WhenShown_ThenTheRefusalIsInsideTheSwapsTable', async () => {
    aQueue({
      'POST /api/swaps/swap-1/approval': {
        status: 409,
        body: { detail: 'That swap request has already been decided.' },
      },
    });
    show();
    await rowOfTheShift();
    const table = screen.getByRole('table');

    await userEvent.click(screen.getByRole('button', { name: /^Approve/ }));

    expect(await within(table).findByRole('alert')).toHaveTextContent(
      'That swap request has already been decided.',
    );
    for (const alert of screen.getAllByRole('alert')) {
      expect(table, 'no copy of the refusal above the table').toContainElement(alert);
    }
  });

  /**
   * C2: somebody else decided the swap first, so the queue read again after the refusal offers
   * nothing to decide. The buttons go even though nothing here succeeded, so focus moves to the
   * row's header cell, and the refusal stays in the row after it.
   */
  it('GivenADecisionRefusedAsDecidedFirst_WhenTheReloadTakesItsButtons_ThenFocusIsOnThatRowsHeader', async () => {
    const stub = routeTo({
      'POST /api/swaps/swap-1/approval': {
        status: 409,
        body: { detail: 'That swap request has already been decided.' },
      },
      // A getter: once the approval has been refused, the server holds the swap decided.
      get '/api/swaps'(): Answer {
        const refused = callsTo(stub, 'POST', '/api/swaps/swap-1/approval').length > 0;
        return { status: 200, body: [refused ? { ...PENDING, status: 'REJECTED' } : PENDING] };
      },
      '/api/shifts/shift-1': { status: 200, body: SHIFT },
    });
    show();
    const row = await rowOfTheShift();

    await userEvent.click(within(row).getByRole('button', { name: /^Approve/ }));

    await waitFor(() =>
      expect(within(row).queryByRole('button', { name: /^Approve/ })).not.toBeInTheDocument(),
    );
    await waitFor(() => expect(within(row).getByRole('rowheader')).toHaveFocus());
    const after = rowAfterTheRowOf(screen.getByRole('table'), WHEN);
    expect(within(after).getByRole('alert')).toHaveTextContent(
      'That swap request has already been decided.',
    );
  });

  /** A second request, for a shift a day later, so its row is told apart from the first's. */
  const NEXT_DAY = {
    ...SHIFT,
    id: 'shift-2',
    start: '2026-10-07T08:00:00Z',
    end: '2026-10-07T16:00:00Z',
  };
  const NEXT_DAY_WHEN = `${timeLabel(NEXT_DAY.start)} to ${timeLabel(NEXT_DAY.end)} on ${dayLabel(NEXT_DAY.start)}`;

  /** Two pending requests, the first of whose approval is refused while it stays pending. */
  function twoRequestsRefusingTheFirst() {
    return aQueue({
      '/api/swaps': {
        status: 200,
        body: [PENDING, { ...PENDING, id: 'swap-2', shift: 'shift-2' }],
      },
      '/api/shifts/shift-2': { status: 200, body: NEXT_DAY },
      'POST /api/swaps/swap-1/approval': {
        status: 422,
        body: {
          detail: 'The roster breaks 1 working-time rule(s)',
          violations: [
            {
              employee: 'emp-2',
              constraint: 'maximum daily working time',
              // Invented, like every limit in a test.
              detail: 'PT16H worked on 2026-10-06 exceeds the maximum of PT10H1M',
            },
          ],
        },
      },
    });
  }

  it('GivenTwoRequestsAndTheFirstRefused_WhenShown_ThenTheRefusalIsInTheRowRightAfterIt', async () => {
    twoRequestsRefusingTheFirst();
    show();
    const row = await rowOfTheShift();
    await rowOfTheShift(NEXT_DAY_WHEN);
    const table = screen.getByRole('table');

    await userEvent.click(within(row).getByRole('button', { name: /^Approve/ }));

    const refusal = await within(table).findByRole('alert');
    expect(within(rowAfterTheRowOf(table, WHEN)).getByRole('alert')).toBe(refusal);
  });

  it('GivenARefusalThatLeavesTheRequestPending_WhenShown_ThenApproveKeepsFocusAndBothCanAct', async () => {
    twoRequestsRefusingTheFirst();
    show();
    const row = await rowOfTheShift();
    const approve = within(row).getByRole('button', { name: /^Approve/ });

    await userEvent.click(approve);

    await within(screen.getByRole('table')).findByRole('alert');
    await waitFor(() => expectActionable(approve));
    expectActionable(within(row).getByRole('button', { name: /^Decline/ }));
    expect(approve, 'a refusal leaves focus where it was').toHaveFocus();
  });

  /** C5: a button waiting for another's answer says why it cannot act yet. */
  it('GivenADecisionInFlight_WhenTheOtherButtonsAreRead_ThenAVisibleHintSaysWhyTheyWait', async () => {
    const release = holdingWrites(twoRequestsRefusingTheFirst());
    show();
    const row = await rowOfTheShift();
    const other = within(await rowOfTheShift(NEXT_DAY_WHEN)).getByRole('button', {
      name: /^Approve/,
    });

    await userEvent.click(within(row).getByRole('button', { name: /^Approve/ }));

    const hint = screen.getByText(/^A decision is on its way/);
    expect(hint).toBeVisible();
    expect(other).toHaveAccessibleDescription(hint.textContent ?? '');
    release();
    await within(screen.getByRole('table')).findByRole('alert');
    expect(screen.queryByText(/^A decision is on its way/)).not.toBeInTheDocument();
  });

  it('GivenFocusMovedWhileADecisionIsOut_WhenItLands_ThenFocusIsNotTaken', async () => {
    const routed = aQueue({ 'POST /api/swaps/swap-1/rejection': { status: 204, body: null } });
    const release = holdingWrites(routed);
    show();
    const row = await rowOfTheShift();
    await userEvent.click(within(row).getByRole('button', { name: /^Decline/ }));
    const elsewhere = screen.getByRole('region', { name: 'Swap requests' });
    elsewhere.focus();

    release();

    await screen.findByText(/^Declined\./);
    expect(elsewhere, 'focus stays where the user moved it').toHaveFocus();
  });
});

describe('the swaps table, for a screen-reader user', () => {
  it('GivenTheSwapsTable_WhenRendered_ThenItHasAnAccessibleName', async () => {
    aQueue();
    show();

    await rowOfTheShift();

    expect(screen.getByRole('table')).toHaveAccessibleName('Swap requests');
  });

  /** C8: a wide table scrolls in a region a keyboard can reach and a screen reader can name. */
  it('GivenTheSwapsTable_WhenRendered_ThenItSitsInANamedFocusableScrollRegion', async () => {
    aQueue();
    show();
    await rowOfTheShift();

    const region = screen.getByRole('region', { name: 'Swap requests' });

    expect(region).toHaveAttribute('tabindex', '0');
    expect(region).toContainElement(screen.getByRole('table'));
  });

  it('GivenNoSwaps_WhenListed_ThenOnlyTheSentenceIsShownWithNoEmptyTable', async () => {
    aQueue({ '/api/swaps': { status: 200, body: [] } });
    show();

    await screen.findByText('Nobody has asked for a swap.');

    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('region', { name: 'Swap requests' }),
      'no empty region to tab into',
    ).not.toBeInTheDocument();
  });
});
