import { useEffect, useId, useRef, useState } from 'react';
import { notifications } from '../api/notifications';
import type { Notification, NotificationKind } from '../api/notifications';
import { scheduling } from '../api/scheduling';
import type { Shift, SwapRequest } from '../api/scheduling';
import { Busy, Failure } from '../components/Feedback';
import { focusIsUnclaimed } from '../components/focus';
import { shiftLabel } from '../components/localDates';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { swapStatusWording } from '../components/swapStatus';
import { useShiftsById } from '../components/useShiftsById';

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
  SWAP_LAPSED: 'A swap you are part of lapsed: its roster was replaced before anybody decided it.',
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
 * on every visit, and twice under StrictMode, whether or not anybody looked. Marking one read says
 * so, and, since its button goes with it, moves focus to the next unread notification's button, or
 * the one before it, or the inbox's heading once none is left. A refusal shows in its notification.
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
  const sessionKey = useSessionKey();
  // A refused mark, shown in the notification it was about.
  const [refused, setRefused] = useState<{ notification: string; error: unknown } | null>(null);
  const [done, setDone] = useState<string | null>(null);
  // The notification being marked. One at a time, since each mark is recorded and each answer reads
  // the inbox again and moves focus.
  const [marking, setMarking] = useState<string | null>(null);
  const markingHint = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  // Each unread notification's button, by the notification's id, for focus to move to.
  const buttons = useRef(new Map<string, HTMLElement>());
  const remember = (notification: string) => (button: HTMLElement | null) => {
    if (button === null) {
      buttons.current.delete(notification);
    } else {
      buttons.current.set(notification, button);
    }
  };
  // A mark stored, until the inbox read again after it shows its button gone.
  const marked = useRef<{ index: number; control: HTMLElement; before: unknown } | null>(null);

  const inbox = useAsync(() => notifications.findMine(), sessionKey);

  // Once the inbox has been read again after a mark: if its button went and nobody has taken the
  // focus since, focus moves to the unread notification that took its place, or the one before it,
  // or the heading once none is left.
  useEffect(() => {
    const pending = marked.current;
    if (pending === null || inbox.busy || inbox.value === pending.before) {
      return;
    }
    marked.current = null;
    if (pending.control.isConnected || !focusIsUnclaimed(pending.control)) {
      return;
    }
    const unread = (inbox.value ?? []).filter((notification) => !notification.read);
    const neighbour = unread[pending.index] ?? unread[pending.index - 1];
    const button = neighbour === undefined ? undefined : buttons.current.get(neighbour.id);
    (button ?? heading.current)?.focus();
  }, [inbox.busy, inbox.value]);

  const swaps = useAsync(() => scheduling.findSwapRequests(), sessionKey);

  const swapById = new Map((swaps.value ?? []).map((swap) => [swap.id, swap]));
  // Only the shifts its notifications are about. A reader who may see every swap in the organisation
  // would otherwise cost one read for each of them.
  const shifts = useShiftsById(
    (inbox.value ?? []).flatMap((notification) => {
      const swap = swapById.get(notification.subject);
      return swap === undefined ? [] : [swap.shift];
    }),
    sessionKey,
  );

  /** @param control the Mark as read pressed, which goes once the inbox is read again */
  async function markRead(notification: Notification, control: HTMLElement) {
    if (marking !== null) {
      return;
    }
    setMarking(notification.id);
    setRefused(null);
    setDone(null);
    const index = (inbox.value ?? [])
      .filter((listed) => !listed.read)
      .findIndex((listed) => listed.id === notification.id);
    try {
      await notifications.markRead(notification.id);
      setDone('Marked as read.');
      marked.current = { index, control, before: inbox.value };
      inbox.reload();
    } catch (error) {
      setRefused({ notification: notification.id, error });
    } finally {
      setMarking(null);
    }
  }

  return (
    <>
      <h3 ref={heading} tabIndex={-1}>
        Inbox
      </h3>
      <p className="hint">
        You are told here, when you next open HrSched. Nothing is sent anywhere else (Q21).
      </p>
      {/* Always in the page, so an outcome is announced when it arrives rather than skipped. */}
      <p className="done" role="status">
        {done}
      </p>
      <Failure error={inbox.error} />
      <Failure error={swaps.error} />
      <Failure error={shifts.error} />
      <Busy busy={inbox.busy} />
      {inbox.value?.length === 0 ? <p>Nothing to read.</p> : null}
      {marking === null ? null : (
        <p className="hint" id={markingHint}>
          Marking one as read. The others wait for its answer.
        </p>
      )}
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
                // aria-disabled rather than disabled, so the pressed one keeps the focus (C1).
                <button
                  ref={remember(notification.id)}
                  type="button"
                  aria-disabled={marking !== null}
                  aria-describedby={
                    marking === null || marking === notification.id ? undefined : markingHint
                  }
                  aria-label={`Mark as read: ${wording} ${at}`}
                  onClick={(event) => void markRead(notification, event.currentTarget)}
                >
                  Mark as read
                </button>
              )}
              {refused?.notification === notification.id ? <Failure error={refused.error} /> : null}
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
    ? `You asked ${nameOf(swap.counterparty)} to take ${when} — ${swapStatusWording(swap.status)}.`
    : `${nameOf(swap.requester)} asked you to take ${when} — ${swapStatusWording(swap.status)}.`;
}
