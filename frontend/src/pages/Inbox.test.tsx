import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dayLabel, timeLabel } from '../components/localDates';
import { routeTo } from '../test-support/routeTo';
import { A_TENANT, SignedIn } from '../test-support/session';
import { Inbox } from './Inbox';

/**
 * The server tells somebody that something happened and holds no words for it (ADR-021), so every
 * sentence here is the interface's, and a kind this build has never heard of must be admitted to
 * rather than guessed at.
 *
 * Reading is an act of the reader. Nothing marks a notification read because it was displayed.
 */

const GRACE = '44444444-4444-4444-8444-444444444444';
const ADA = '11111111-1111-4111-8111-111111111111';

const SHIFT = {
  id: 'shift-1',
  start: '2026-10-06T08:00:00Z',
  end: '2026-10-06T16:00:00Z',
  zone: 'UTC',
  requiredHeadcount: 1,
  requiredQualifications: [],
};

const SWAP = {
  id: 'swap-1',
  roster: 'roster-1',
  shift: 'shift-1',
  requester: ADA,
  counterparty: GRACE,
  status: 'REQUESTED',
};

/** What Grace is told about Ada's request once its shift has been read — time and day included. */
const ASKED_BY_ADA = `Ada asked you to take ${timeLabel(SHIFT.start)} to ${timeLabel(SHIFT.end)} on ${dayLabel(SHIFT.start)} — waiting on a Scheduler.`;

function told(kind: string, read = false) {
  return { id: 'n-1', kind, subject: SWAP.id, occurredAt: '2026-10-01T09:30:00Z', read };
}

/**
 * The inbox the server holds, which marking read really changes, so what is shown afterwards is what
 * the server says rather than what the page assumed.
 */
function serving(inbox: ReturnType<typeof told>[], swaps: unknown[] = [SWAP]) {
  let held = inbox;
  const stub = vi.fn((path: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    let body: unknown = [];
    let status = 200;
    if (method === 'POST' && path === '/api/notifications/n-1/read') {
      held = held.map((notification) => ({ ...notification, read: true }));
      status = 204;
      body = null;
    } else if (path === '/api/notifications') {
      body = held;
    } else if (path === '/api/swaps') {
      body = swaps;
    } else if (path === '/api/shifts/shift-1') {
      body = SHIFT;
    } else {
      status = 404;
      body = {};
    }
    return Promise.resolve({
      ok: status < 400,
      status,
      headers: new Headers(),
      text: () => Promise.resolve(body === null ? '' : JSON.stringify(body)),
    });
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

function show() {
  render(
    <SignedIn session={A_TENANT}>
      <Inbox employee={GRACE} nameOf={(employee) => (employee === ADA ? 'Ada' : employee)} />
    </SignedIn>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('the words for what happened', () => {
  it.each([
    ['SWAP_REQUESTED', 'A colleague has asked you to take one of their shifts.'],
    ['SWAP_APPROVED', 'A swap you are part of was approved, and the roster changed.'],
    ['SWAP_REJECTED', 'A swap you are part of was declined. Nothing changed.'],
    [
      'SWAP_LAPSED',
      'A swap you are part of lapsed: its roster was replaced before anybody decided it.',
    ],
  ])(
    'GivenEachKind_WhenRendered_ThenTheInterfaceSuppliesItsWording (%s)',
    async (kind, wording) => {
      serving([told(kind)]);

      show();

      expect(await screen.findByText(wording)).toBeInTheDocument();
    },
  );

  it('GivenAKindThisBuildDoesNotKnow_WhenRendered_ThenItSaysSoRatherThanGuessing', async () => {
    serving([told('SHIFT_CANCELLED')]);

    show();

    expect(await screen.findByText(/cannot describe \(SHIFT_CANCELLED\)/)).toBeInTheDocument();
  });

  it('GivenNothingHasHappened_WhenRendered_ThenItSaysThereIsNothingToRead', async () => {
    serving([]);

    show();

    expect(await screen.findByText('Nothing to read.')).toBeInTheDocument();
  });
});

describe('what it is about', () => {
  it('GivenAVisibleSwap_WhenRendered_ThenItsStatusShiftTimeAndOtherPartyAreShown', async () => {
    serving([told('SWAP_REQUESTED')]);

    show();

    expect(
      await screen.findByText(
        `Ada asked you to take ${timeLabel(SHIFT.start)} to ${timeLabel(SHIFT.end)} on ${dayLabel(SHIFT.start)} — waiting on a Scheduler.`,
      ),
    ).toBeInTheDocument();
  });

  /** A swap the reader may no longer see is not invented; the notification still stands on its own. */
  it('GivenASwapTheReaderCannotSee_WhenRendered_ThenOnlyTheNotificationIsShown', async () => {
    serving([told('SWAP_REQUESTED')], []);

    show();

    expect(
      await screen.findByText('A colleague has asked you to take one of their shifts.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/asked you to take .* on /)).not.toBeInTheDocument();
  });
});

describe('what it reads', () => {
  /**
   * A reader who is also a Scheduler can see every swap in the organisation; the inbox needs the
   * shifts of the few its notifications are about, not one read per swap anybody ever raised.
   */
  it('GivenSwapsTheInboxIsNotAbout_WhenOpened_ThenTheirShiftsAreNotRead', async () => {
    const elsewhere = { ...SWAP, id: 'swap-2', shift: 'shift-2' };
    const stub = serving([told('SWAP_REQUESTED')], [SWAP, elsewhere]);

    show();

    await screen.findByText(ASKED_BY_ADA);
    expect(stub.mock.calls.map(([path]) => path)).not.toContain('/api/shifts/shift-2');
  });
});

describe('reading', () => {
  it('GivenAnUnreadNotification_WhenMarkedRead_ThenItIsPostedAndShownAsRead', async () => {
    const stub = serving([told('SWAP_REQUESTED')]);
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^Mark as read/ }));

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /^Mark as read/ })).not.toBeInTheDocument(),
    );
    expect(screen.getByText(/· read$/)).toBeInTheDocument();
    expect(
      stub.mock.calls.filter(
        ([path, init]) => init?.method === 'POST' && path === '/api/notifications/n-1/read',
      ),
    ).toHaveLength(1);
  });

  /** A refusal left unshown would read as done, and the notification would come back unread. */
  it('GivenMarkingReadIsRefused_WhenMarkedRead_ThenTheRefusalIsShownAndItStaysUnread', async () => {
    routeTo({
      '/api/notifications': { status: 200, body: [told('SWAP_REQUESTED')] },
      '/api/swaps': { status: 200, body: [SWAP] },
      '/api/shifts/shift-1': { status: 200, body: SHIFT },
      'POST /api/notifications/n-1/read': {
        status: 404,
        body: { detail: 'No such resource.' },
      },
    });
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^Mark as read/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Nothing here for this organisation.',
    );
    expect(screen.getByText(/· unread$/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Mark as read/ })).toBeInTheDocument();
  });

  it('GivenTheInboxOpens_WhenNothingIsClicked_ThenNothingIsMarkedRead', async () => {
    const stub = serving([told('SWAP_REQUESTED')]);

    show();

    await screen.findByRole('button', { name: /^Mark as read/ });
    // Waits until the swap it is about has been read too, so every read the inbox makes has happened.
    await screen.findByText(ASKED_BY_ADA);
    expect(stub.mock.calls.some(([, init]) => (init?.method ?? 'GET') !== 'GET')).toBe(false);
  });
});
