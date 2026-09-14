import { useState } from 'react';
import { identityKey } from '../api/client';
import { scheduling } from '../api/scheduling';
import type { Employee } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { dayLabel, timeLabel } from '../components/localDates';
import { useAsync } from '../components/useAsync';
import { useActingAs } from '../identity/ActingAs';
import { SWAP_STATUS } from './swapStatus';

/**
 * Scheduler: answer the swap requests (F7).
 *
 * Approving publishes a replacement roster rather than editing the one people are working to
 * (ADR-022), so it is refused exactly as publishing is — every broken rule named, and no override.
 * The buttons are offered to every role: whether this one may decide is the backend's to say.
 *
 * Names come from the people the acting role may list, and fall back to identifiers, rather than
 * the page deciding from the role's name who is allowed to see what.
 */
export function SwapsPage({ people }: { people: readonly Employee[] }) {
  const { identity } = useActingAs();
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const swaps = useAsync(() => scheduling.findSwapRequests(identity), identityKey(identity));
  const shiftIds = [...new Set((swaps.value ?? []).map((swap) => swap.shift))].sort();
  const shifts = useAsync(
    () => Promise.all(shiftIds.map((id) => scheduling.findShift(identity, id))),
    `${identityKey(identity)}|${shiftIds.join(',')}`,
  );
  const shiftById = new Map((shifts.value ?? []).map((shift) => [shift.id, shift]));
  const personById = new Map(people.map((person) => [person.id, person]));
  const nameOf = (employee: string): string => personById.get(employee)?.name ?? employee;

  async function decide(action: () => Promise<unknown>, outcome: string) {
    setFailure(null);
    setDone(null);
    try {
      await action();
      setDone(outcome);
    } catch (error) {
      setFailure(error);
    } finally {
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

      <Failure error={failure} nameOf={nameOf} />
      <p className="done" role="status">
        {done}
      </p>
      <Failure error={swaps.error} />
      <Failure error={shifts.error} />
      <Busy busy={swaps.busy} />
      {swaps.value?.length === 0 ? <p>Nobody has asked for a swap.</p> : null}

      <table>
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
          {(swaps.value ?? []).map((swap) => {
            const shift = shiftById.get(swap.shift);
            const when =
              shift === undefined
                ? 'A shift'
                : `${timeLabel(shift.start)} to ${timeLabel(shift.end)} on ${dayLabel(shift.start)}`;
            // Every row repeats the same two buttons, so each one's name says which request it answers.
            const what = `${nameOf(swap.requester)} hands ${when} to ${nameOf(swap.counterparty)}`;
            return (
              <tr key={swap.id}>
                <td>{when}</td>
                <td>{nameOf(swap.requester)}</td>
                <td>{nameOf(swap.counterparty)}</td>
                <td>{SWAP_STATUS[swap.status]}</td>
                <td>
                  {swap.status === 'REQUESTED' ? (
                    <>
                      <button
                        type="button"
                        aria-label={`Approve: ${what}`}
                        onClick={() =>
                          void decide(
                            () => scheduling.approveSwap(identity, swap.id),
                            'Approved. A replacement roster was published, and both of them are told.',
                          )
                        }
                      >
                        Approve
                      </button>{' '}
                      <button
                        type="button"
                        aria-label={`Decline: ${what}`}
                        onClick={() =>
                          void decide(
                            () => scheduling.rejectSwap(identity, swap.id),
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
            );
          })}
        </tbody>
      </table>
    </section>
  );
}
