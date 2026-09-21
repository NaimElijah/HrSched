import { useState } from 'react';
import { notifications } from '../api/notifications';
import type { Notification, NotificationKind } from '../api/notifications';
import { scheduling } from '../api/scheduling';
import type { Shift, SwapRequest } from '../api/scheduling';
import { Busy, Failure } from '../components/Feedback';
import { shiftLabel } from '../components/localDates';
import { useAsync } from '../components/useAsync';
import { useActingAs, useIdentityKey } from '../identity/ActingAs';
import { SWAP_STATUS } from './swapStatus';
import { useShiftsById } from './useShiftsById';

/**
 * The words for each kind of notification.
 *
 * The server holds none, on purpose (ADR-021): a notification that stored a sentence would be the
 * first place a colleague's name reached storage. A `Record` over the kinds, so a kind added to the
 * type cannot compile without words of its own.
 */
const WORDING: Record<NotificationKind, string> = {
  SWAP_REQUESTED: 'A colleague has asked you to take one of their shifts.',
  SWAP_APPROVED: 'A swap you are part of was approved, and the roster changed.',
  SWAP_REJECTED: 'A swap you are part of was declined. Nothing changed.',
};

function isKnownKind(kind: string): kind is NotificationKind {
  return Object.prototype.hasOwnProperty.call(WORDING, kind);
}

function wordingOf(kind: string): string {
  return isKnownKind(kind)
    ? WORDING[kind]
    : `Something happened that this version of HrSched cannot describe (${kind}).`;
}

/**
 * The employee's own notifications, in words this interface supplies, with the swap each one is about
 * when the reader may still see it.
 *
 * Reading is the reader's act. Opening the inbox marks nothing: an effect would mark everything read
 * on every visit, and twice under StrictMode, whether or not anybody looked.
 */
export function Inbox({
  employee,
  nameOf,
}: {
  /** Whose inbox this is, so a swap can say who the other party was. */
  employee: string;
  /** Names a colleague where the acting role may list people; otherwise gives back the identifier. */
  nameOf: (employee: string) => string;
}) {
  const { identity } = useActingAs();
  const identityKey = useIdentityKey();
  const [failure, setFailure] = useState<unknown>(null);

  const inbox = useAsync(() => notifications.findMine(), identityKey);
  const swaps = useAsync(() => scheduling.findSwapRequests(), identityKey);

  const swapById = new Map((swaps.value ?? []).map((swap) => [swap.id, swap]));
  // Only the shifts its notifications are about. A reader who may see every swap in the organisation
  // would otherwise cost one read for each of them.
  const shifts = useShiftsById(
    identity,
    (inbox.value ?? []).flatMap((notification) => {
      const swap = swapById.get(notification.subject);
      return swap === undefined ? [] : [swap.shift];
    }),
  );

  async function markRead(notification: Notification) {
    setFailure(null);
    try {
      await notifications.markRead(notification.id);
      inbox.reload();
    } catch (error) {
      setFailure(error);
    }
  }

  return (
    <>
      <h3>Inbox</h3>
      <p className="hint">
        You are told here, when you next open HrSched. Nothing is sent anywhere else (Q21).
      </p>
      <Failure error={failure} />
      <Failure error={inbox.error} />
      <Failure error={swaps.error} />
      <Failure error={shifts.error} />
      <Busy busy={inbox.busy} />
      {inbox.value?.length === 0 ? <p>Nothing to read.</p> : null}
      <ul className="inbox">
        {(inbox.value ?? []).map((notification) => {
          const wording = wordingOf(notification.kind);
          const at = new Date(notification.occurredAt).toLocaleString();
          const swap = swapById.get(notification.subject);
          return (
            <li key={notification.id}>
              <p>{wording}</p>
              {swap === undefined ? null : (
                <p>{describe(swap, employee, shifts.byId.get(swap.shift), nameOf)}</p>
              )}
              <p className="hint">{`${at} · ${notification.read ? 'read' : 'unread'}`}</p>
              {notification.read ? null : (
                <button
                  type="button"
                  aria-label={`Mark as read: ${wording} ${at}`}
                  onClick={() => void markRead(notification)}
                >
                  Mark as read
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </>
  );
}

function describe(
  swap: SwapRequest,
  reader: string,
  shift: Shift | undefined,
  nameOf: (employee: string) => string,
): string {
  const when = shift === undefined ? 'a shift' : shiftLabel(shift.start, shift.end);
  return swap.requester === reader
    ? `You asked ${nameOf(swap.counterparty)} to take ${when} — ${SWAP_STATUS[swap.status]}.`
    : `${nameOf(swap.requester)} asked you to take ${when} — ${SWAP_STATUS[swap.status]}.`;
}
