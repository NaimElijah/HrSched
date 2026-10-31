import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { dayLabel, timeLabel } from '../components/localDates';
import { callsTo, holdingWrites, routeTo, writes } from '../test-support/routeTo';
import type { Answer } from '../test-support/routeTo';
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
  const stub = routeTo({
    'POST /api/notifications/n-1/read': { status: 204, body: null },
    // A getter, so the listing is worked out on each request from the marks received so far.
    get '/api/notifications'(): Answer {
      const marked = callsTo(stub, 'POST', '/api/notifications/n-1/read').length > 0;
      return {
        status: 200,
        body: marked ? inbox.map((notification) => ({ ...notification, read: true })) : inbox,
      };
    },
    '/api/swaps': { status: 200, body: swaps },
    '/api/shifts/shift-1': { status: 200, body: SHIFT },
  });
  return stub;
}

function show() {
  render(
    <SignedIn session={A_TENANT}>
      <Inbox employee={GRACE} nameOf={(employee) => (employee === ADA ? 'Ada' : employee)} />
    </SignedIn>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
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

  /**
   * A kind that happens to name a property every object inherits is still unknown: it has no
   * wording of its own, so it reads as any other unknown kind would.
   */
  it.each(['constructor', 'toString'])(
    'GivenAKindNamingAnInheritedProperty_WhenRendered_ThenItSaysSoRatherThanGuessing (%s)',
    async (kind) => {
      serving([told(kind)]);

      show();

      expect(
        await screen.findByText(
          `Something happened that this version of HrSched cannot describe (${kind}).`,
        ),
      ).toBeInTheDocument();
    },
  );

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

  /** A status a newer server sends is named, never rendered as "undefined". Invented for the test. */
  it('GivenASwapStatusThisBuildDoesNotKnow_WhenRendered_ThenItSaysSoByName', async () => {
    serving([told('SWAP_REQUESTED')], [{ ...SWAP, status: 'ESCALATED' }]);

    show();

    expect(await screen.findByText(/cannot describe \(ESCALATED\)\.$/)).toBeInTheDocument();
    expect(screen.queryByText(/undefined/)).not.toBeInTheDocument();
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

  /** A shift's time read under one account is not what the next account to sign in is shown. */
  it('GivenShiftsShown_WhenTheSessionChanges_ThenTheShiftsAreReadAgain', async () => {
    const stub = serving([told('SWAP_REQUESTED')]);
    const inbox = (
      <Inbox employee={GRACE} nameOf={(employee) => (employee === ADA ? 'Ada' : employee)} />
    );
    const { rerender } = render(<SignedIn session={A_TENANT}>{inbox}</SignedIn>);
    await screen.findByText(ASKED_BY_ADA);
    expect(callsTo(stub, 'GET', '/api/shifts/shift-1')).toHaveLength(1);

    rerender(
      <SignedIn session={{ ...A_TENANT, userId: '99999999-9999-4999-8999-999999999999' }}>
        {inbox}
      </SignedIn>,
    );

    await waitFor(() => expect(callsTo(stub, 'GET', '/api/shifts/shift-1')).toHaveLength(2));
    await screen.findByText(ASKED_BY_ADA);
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

// --- #257 (WCAG 2.2 AA): reading, as a keyboard and screen-reader user meets it ---------------

/** C3: the live region that says this. Several are on the page, so each is looked at. */
function findStatus(saying: RegExp): Promise<HTMLElement> {
  return waitFor(() => {
    const statuses = screen.getAllByRole('status');
    const found = statuses.find((status) => saying.test(status.textContent ?? ''));
    if (found === undefined) {
      throw new Error(
        `no status says ${String(saying)}; the statuses say ${JSON.stringify(statuses.map((status) => status.textContent))}`,
      );
    }
    return found;
  });
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

/** The notification's list item a control sits in. */
function itemOf(control: HTMLElement): HTMLElement {
  const item = screen.getAllByRole('listitem').find((candidate) => candidate.contains(control));
  if (item === undefined) {
    throw new Error('the control is not in a list item');
  }
  return item;
}

/** Three unread notifications an hour apart, each marked read on the server once it is posted. */
const THREE = ['n-1', 'n-2', 'n-3'].map((id, hour) => ({
  id,
  kind: 'SWAP_REQUESTED',
  subject: SWAP.id,
  occurredAt: `2026-10-01T0${String(hour + 1)}:30:00Z`,
  read: false,
}));

/** The Mark as read of the one of {@link THREE} at this place, by the time its name ends with. */
function markReadOf(index: number): HTMLElement {
  const notification = THREE[index];
  if (notification === undefined) {
    throw new Error(`no notification at ${String(index)}`);
  }
  return screen.getByRole('button', {
    name: `Mark as read: A colleague has asked you to take one of their shifts. ${new Date(notification.occurredAt).toLocaleString()}`,
  });
}

function servingThree() {
  const marks = Object.fromEntries(
    THREE.map(({ id }) => [`POST /api/notifications/${id}/read`, { status: 204, body: null }]),
  );
  const stub = routeTo({
    ...marks,
    get '/api/notifications'(): Answer {
      return {
        status: 200,
        body: THREE.map((notification) => ({
          ...notification,
          read: callsTo(stub, 'POST', `/api/notifications/${notification.id}/read`).length > 0,
        })),
      };
    },
    '/api/swaps': { status: 200, body: [SWAP] },
    '/api/shifts/shift-1': { status: 200, body: SHIFT },
  });
  return stub;
}

describe('reading, for a keyboard and screen-reader user', () => {
  /** C2: Mark as read goes once the notification is read, so focus moves to the inbox's heading. */
  it('GivenAnUnreadNotification_WhenMarkedRead_ThenFocusIsOnTheInboxHeading', async () => {
    serving([told('SWAP_REQUESTED')]);
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^Mark as read/ }));

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /^Mark as read/ })).not.toBeInTheDocument(),
    );
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Inbox' })).toHaveFocus());
  });

  it('GivenAnUnreadNotification_WhenMarkedRead_ThenAStatusSaysItWasMarkedAsRead', async () => {
    serving([told('SWAP_REQUESTED')]);
    show();

    await screen.findByRole('button', { name: /^Mark as read/ });
    const statusesBefore = screen.getAllByRole('status');

    await userEvent.click(screen.getByRole('button', { name: /^Mark as read/ }));

    const status = await findStatus(/^Marked as read\.$/);
    // C3: a live region that appeared with its text would not be announced.
    expect(statusesBefore, 'the status was in the page before it said anything').toContain(status);
  });

  /** Each mark is audited, so a second press while the first is out must not send a second. */
  it('GivenAMarkInFlight_WhenMarkAsReadIsPressedAgain_ThenItWaitsKeepsFocusAndSendsOnce', async () => {
    const routed = servingThree();
    const release = holdingWrites(routed);
    show();
    await screen.findAllByRole('button', { name: /^Mark as read/ });
    const first = markReadOf(0);
    const second = markReadOf(1);

    await userEvent.click(first);
    await userEvent.click(first);

    expectWaiting(first);
    expect(first, 'the pressed button keeps the keyboard focus').toHaveFocus();
    expectWaiting(second);
    await userEvent.click(second);
    release();
    await findStatus(/^Marked as read\.$/);
    await waitFor(() => expectActionable(second));
    expect(writes(routed), 'one mark was sent').toHaveLength(1);
  });

  /** C5: a button waiting for another's answer says why it cannot act yet. */
  it('GivenAMarkInFlight_WhenTheOtherButtonsAreRead_ThenAVisibleHintSaysWhyTheyWait', async () => {
    const release = holdingWrites(servingThree());
    show();
    await screen.findAllByRole('button', { name: /^Mark as read/ });
    const first = markReadOf(0);
    const second = markReadOf(1);

    await userEvent.click(first);

    const hint = screen.getByText(/^Marking one as read/);
    expect(hint).toBeVisible();
    expect(second).toHaveAccessibleDescription(hint.textContent ?? '');
    release();
    await findStatus(/^Marked as read\.$/);
    expect(screen.queryByText(/^Marking one as read/)).not.toBeInTheDocument();
  });

  /** C6, C2: a refusal is of one notification, so it shows in its item, and its button stays. */
  it('GivenMarkingReadIsRefused_WhenShown_ThenTheRefusalIsInThatItemAndFocusStaysOnTheButton', async () => {
    routeTo({
      '/api/notifications': { status: 200, body: THREE },
      '/api/swaps': { status: 200, body: [SWAP] },
      '/api/shifts/shift-1': { status: 200, body: SHIFT },
      'POST /api/notifications/n-2/read': { status: 404, body: { detail: 'No such resource.' } },
    });
    show();
    await screen.findAllByRole('button', { name: /^Mark as read/ });
    const pressed = markReadOf(1);

    await userEvent.click(pressed);

    const item = itemOf(pressed);
    expect(await within(item).findByRole('alert')).toHaveTextContent(
      'Nothing here for this organisation.',
    );
    for (const alert of screen.getAllByRole('alert')) {
      expect(item, 'no copy of the refusal above the list').toContainElement(alert);
    }
    expectActionable(pressed);
    expect(pressed, 'a refusal leaves focus where it was').toHaveFocus();
    for (const status of screen.getAllByRole('status')) {
      expect(status).not.toHaveTextContent('Marked as read.');
    }
  });

  /** C2: the next notification is where a keyboard user was going, so focus moves there. */
  it.each([
    ['the first', 0, 1],
    ['a middle one', 1, 2],
    ['the last', 2, 1],
  ])(
    'GivenThreeUnread_WhenOneIsMarkedRead_ThenFocusIsOnTheNeighboursButton (%s)',
    async (_which, marked, neighbour) => {
      servingThree();
      show();
      await screen.findAllByRole('button', { name: /^Mark as read/ });
      const pressed = markReadOf(marked);
      const next = markReadOf(neighbour);

      await userEvent.click(pressed);

      await waitFor(() => expect(pressed).not.toBeInTheDocument());
      await waitFor(() => expect(next).toHaveFocus());
    },
  );

  it('GivenFocusMovedWhileAMarkIsOut_WhenItIsStored_ThenFocusIsNotTaken', async () => {
    const release = holdingWrites(servingThree());
    show();
    await screen.findAllByRole('button', { name: /^Mark as read/ });
    const pressed = markReadOf(0);
    await userEvent.click(pressed);
    const elsewhere = screen.getByRole('heading', { name: 'Inbox' });
    elsewhere.focus();

    release();

    await waitFor(() => expect(pressed).not.toBeInTheDocument());
    expect(elsewhere, 'focus stays where the user moved it').toHaveFocus();
  });
});
