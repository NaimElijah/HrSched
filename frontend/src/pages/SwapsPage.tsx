import { Fragment, useEffect, useId, useRef, useState } from 'react';
import { scheduling } from '../api/scheduling';
import type { Employee } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { focusIsUnclaimed } from '../components/focus';
import { shiftLabel } from '../components/localDates';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { swapStatusWording } from '../components/swapStatus';
import { useShiftsById } from '../components/useShiftsById';

/** The swaps table's columns, which a row spanning all of them has to know. */
const SWAP_COLUMNS = 5;

/**
 * Scheduler: answer the swap requests (F17).
 *
 * Approving publishes a replacement roster rather than editing the one people are working to
 * (ADR-022), so it is refused exactly as publishing is — every broken rule named, and no override.
 * The buttons are offered to every role: whether this one may decide is the backend's to say.
 *
 * Names come from the people the acting role may list, and fall back to identifiers, rather than
 * the page deciding from the role's name who is allowed to see what.
 *
 * A refused decision is shown in the row right after the request it answered, and a decision made
 * moves focus to that request's row, whose buttons go once it is decided. A refused one moves it
 * there too when the queue read again shows the request decided by somebody else, since its
 * buttons go then as well.
 */
export function SwapsPage({ people }: { people: readonly Employee[] }) {
  const sessionKey = useSessionKey();
  // A refused decision, shown at the row of the request it answered.
  const [refused, setRefused] = useState<{ swap: string; error: unknown } | null>(null);
  const [done, setDone] = useState<string | null>(null);
  // One decision at a time: a second approval racing the first could publish a second replacement.
  const [deciding, setDeciding] = useState(false);
  const caption = useId();
  const decidingHint = useId();
  // Each row's header cell, by the swap's id, for focus to move to once its buttons go.
  const rowHeaders = useRef(new Map<string, HTMLElement>());
  const remember = (swap: string) => (cell: HTMLElement | null) => {
    if (cell === null) {
      rowHeaders.current.delete(swap);
    } else {
      rowHeaders.current.set(swap, cell);
    }
  };

  // The button of a refused decision, until the queue read again after it says whether that
  // button is still there.
  const refusedFrom = useRef<{ swap: string; control: HTMLElement } | null>(null);

  const swaps = useAsync(() => scheduling.findSwapRequests(), sessionKey);

  useEffect(() => {
    const pending = refusedFrom.current;
    if (pending === null || swaps.busy) {
      return;
    }
    refusedFrom.current = null;
    if (!pending.control.isConnected && focusIsUnclaimed(pending.control)) {
      rowHeaders.current.get(pending.swap)?.focus();
    }
  }, [swaps.busy, swaps.value]);
  const shifts = useShiftsById(
    (swaps.value ?? []).map((swap) => swap.shift),
    sessionKey,
  );
  const personById = new Map(people.map((person) => [person.id, person]));
  const nameOf = (employee: string): string => personById.get(employee)?.name ?? employee;
  const listed = swaps.value ?? [];

  /**
   * @param swap the request decided, where a refusal is shown and focus moves once it is decided
   * @param control the button pressed, which goes once the list shows the request decided
   */
  async function decide(
    swap: string,
    control: HTMLElement,
    action: () => Promise<unknown>,
    outcome: string,
  ) {
    if (deciding) {
      return;
    }
    setDeciding(true);
    setRefused(null);
    refusedFrom.current = null;
    setDone(null);
    try {
      await action();
      setDone(outcome);
      if (focusIsUnclaimed(control)) {
        rowHeaders.current.get(swap)?.focus();
      }
    } catch (error) {
      setRefused({ swap, error });
      refusedFrom.current = { swap, control };
    } finally {
      setDeciding(false);
      // Either way the queue may have moved: a refusal can mean somebody else decided it first.
      swaps.reload();
    }
  }

  return (
    <section>
      <h2>Swaps</h2>
      <p className="hint">
        Approving publishes a replacement roster that supersedes the one people are working to. It
        is refused if the replacement would break a rule, and there is no override. The colleague
        was told when they were asked, and both are told what you decide.
      </p>

      <p className="done" role="status">
        {done}
      </p>
      <Failure error={swaps.error} />
      <Failure error={shifts.error} />
      <Busy busy={swaps.busy} />
      {swaps.value?.length === 0 ? <p>Nobody has asked for a swap.</p> : null}
      {deciding ? (
        <p className="hint" id={decidingHint}>
          A decision is on its way. Deciding waits for its answer.
        </p>
      ) : null}

      {/* Only a list with rows gets the region: an empty one would be a stop with nothing in it. */}
      {listed.length === 0 ? null : (
        <div className="table-scroll" role="region" aria-labelledby={caption} tabIndex={0}>
          <table>
            {/* Hidden because the heading above already says it; the table still needs a name. */}
            <caption id={caption} className="visually-hidden">
              Swap requests
            </caption>
            <thead>
              <tr>
                <th>Shift</th>
                <th>Asked by</th>
                <th>To be taken by</th>
                <th>Where it stands</th>
                <th>
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {listed.map((swap) => {
                const shift = shifts.byId.get(swap.shift);
                const when = shift === undefined ? 'A shift' : shiftLabel(shift.start, shift.end);
                // Every row repeats the same two buttons, so each one's name says which request it answers.
                const what = `${nameOf(swap.requester)} hands ${when} to ${nameOf(swap.counterparty)}`;
                return (
                  <Fragment key={swap.id}>
                    <tr>
                      {/* Focusable, so focus has somewhere to go when this row's buttons go. */}
                      <th scope="row" tabIndex={-1} ref={remember(swap.id)}>
                        {when}
                      </th>
                      <td>{nameOf(swap.requester)}</td>
                      <td>{nameOf(swap.counterparty)}</td>
                      <td>{swapStatusWording(swap.status)}</td>
                      <td>
                        {swap.status === 'REQUESTED' ? (
                          <>
                            <button
                              type="button"
                              aria-disabled={deciding}
                              aria-describedby={deciding ? decidingHint : undefined}
                              aria-label={`Approve: ${what}`}
                              onClick={(event) =>
                                void decide(
                                  swap.id,
                                  event.currentTarget,
                                  () => scheduling.approveSwap(swap.id),
                                  'Approved. A replacement roster was published, and both of them are told.',
                                )
                              }
                            >
                              Approve
                            </button>{' '}
                            <button
                              type="button"
                              aria-disabled={deciding}
                              aria-describedby={deciding ? decidingHint : undefined}
                              aria-label={`Decline: ${what}`}
                              onClick={(event) =>
                                void decide(
                                  swap.id,
                                  event.currentTarget,
                                  () => scheduling.rejectSwap(swap.id),
                                  'Declined. Nothing changed, and both of them are told.',
                                )
                              }
                            >
                              Decline
                            </button>
                          </>
                        ) : null}
                      </td>
                    </tr>
                    {refused?.swap === swap.id ? (
                      <tr>
                        <td colSpan={SWAP_COLUMNS}>
                          {/* Pinned to the visible part of the scroll region, near the buttons. */}
                          <div className="row-refusal">
                            <Failure error={refused.error} nameOf={nameOf} />
                          </div>
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
