import { useState } from 'react';
import { scheduling } from '../api/scheduling';
import type { Roster, Shift } from '../api/scheduling';
import { workforce } from '../api/workforce';
import type { Employee } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { identityKey } from '../api/client';
import { useActingAs } from '../identity/ActingAs';

/**
 * Scheduler: generate a draft, read it, publish it.
 *
 * Publication is the part that matters. A roster breaking the rules is refused with every violation
 * listed and there is no override, so the refusal is rendered inline as a first-class outcome
 * rather than as an error toast to dismiss.
 *
 * Generation is greedy and optimises nothing (ADR-005), which this page says plainly rather than
 * letting a scheduler assume the result is good.
 */
export function RostersPage({ span }: { span: { from: string; to: string } }) {
  const { identity } = useActingAs();
  const [failure, setFailure] = useState<unknown>(null);
  const [selected, setSelected] = useState<string | null>(null);

  const rosters = useAsync(() => scheduling.findRosters(identity), identityKey(identity));
  const shifts = useAsync(
    () => scheduling.findShifts(identity, span.from, span.to),
    `${identityKey(identity)}|${span.from}|${span.to}`,
  );
  const people = useAsync(() => workforce.findEmployees(identity), identityKey(identity));

  const shiftById = new Map((shifts.value ?? []).map((shift) => [shift.id, shift]));
  const personById = new Map((people.value ?? []).map((person) => [person.id, person]));

  async function act(action: () => Promise<unknown>) {
    setFailure(null);
    try {
      await action();
      rosters.reload();
    } catch (error) {
      setFailure(error);
    }
  }

  const chosen = (rosters.value ?? []).find((roster) => roster.id === selected) ?? null;

  return (
    <section>
      <h2>Rosters</h2>
      <p className="hint">
        Generation is greedy: it takes the first eligible person and optimises nothing — not
        fairness, not preferences, not disruption. It leaves a shift short rather than filling it
        illegally.
      </p>

      <button
        type="button"
        onClick={() => void act(() => scheduling.generateRoster(identity, span.from, span.to))}
      >
        Generate a draft for this span
      </button>

      <Failure error={failure} />
      <Failure error={rosters.error} />
      <Busy busy={rosters.busy} />

      <table>
        <thead>
          <tr>
            <th>Roster</th>
            <th>Status</th>
            <th>Assignments</th>
            <th>Approved under</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {(rosters.value ?? []).map((roster) => (
            <tr key={roster.id}>
              <td>
                <code>{roster.id}</code>
              </td>
              <td>{roster.status}</td>
              <td>{roster.assignments.length}</td>
              <td>{roster.validatedAgainstConstraintVersion ?? '—'}</td>
              <td>
                <button type="button" onClick={() => setSelected(roster.id)}>
                  Open
                </button>
                {roster.status === 'DRAFT' ? (
                  <button
                    type="button"
                    onClick={() => void act(() => scheduling.publishRoster(identity, roster.id))}
                  >
                    Publish
                  </button>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {chosen === null ? null : (
        <RosterDetail roster={chosen} shiftById={shiftById} personById={personById} />
      )}
    </section>
  );
}

function RosterDetail({
  roster,
  shiftById,
  personById,
}: {
  roster: Roster;
  shiftById: Map<string, Shift>;
  personById: Map<string, Employee>;
}) {
  return (
    <div className="detail">
      <h3>Roster {roster.status.toLowerCase()}</h3>
      {roster.assignments.length === 0 ? (
        <p>
          Nothing assigned. Either no shift falls in this span, or nobody eligible was available —
          an uncoverable shift is left short rather than filled illegally.
        </p>
      ) : (
        <ul>
          {roster.assignments.map((assignment, index) => {
            const shift = shiftById.get(assignment.shift);
            const person = personById.get(assignment.employee);
            const when =
              shift === undefined
                ? assignment.shift
                : `${new Date(shift.start).toLocaleString()} to ${new Date(shift.end).toLocaleString()}`;
            return (
              <li key={index}>
                {person?.name ?? assignment.employee} — {when}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
