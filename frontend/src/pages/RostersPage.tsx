import { useState } from 'react';
import { scheduling } from '../api/scheduling';
import type { Employee } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useIdentityKey } from '../identity/ActingAs';
import { RosterBoard } from './RosterBoard';

/**
 * Scheduler: generate a draft, change it by hand, publish it.
 *
 * Publication is the part that matters. A roster breaking the rules is refused with every violation
 * listed and there is no override, so the refusal is rendered inline as a first-class outcome
 * rather than as an error toast to dismiss.
 *
 * Generation is greedy and optimises nothing (ADR-005), which this page says plainly rather than
 * letting a scheduler assume the result is good.
 *
 * The people come from the shell, which already loads them and already decides which refusal of
 * that listing to keep quiet about; asking again here doubled the request and repeated its failure.
 */
export function RostersPage({
  span,
  people,
}: {
  span: { from: string; to: string };
  /** Everybody the acting role may list, to name assignees and offer on the board. */
  people: readonly Employee[];
}) {
  const identityKey = useIdentityKey();
  const [failure, setFailure] = useState<unknown>(null);
  const [selected, setSelected] = useState<string | null>(null);
  // Bumped whenever the list changes a roster, so an open board is rebuilt from what is now stored.
  // A change made on the board does not bump it: the board already holds what the server returned.
  const [revision, setRevision] = useState(0);

  const rosters = useAsync(() => scheduling.findRosters(), identityKey);
  const personById = new Map(people.map((person) => [person.id, person]));
  const nameOf = (employee: string): string | undefined => personById.get(employee)?.name;

  async function act(action: () => Promise<unknown>) {
    setFailure(null);
    try {
      await action();
      rosters.reload();
      setRevision((previous) => previous + 1);
    } catch (error) {
      setFailure(error);
    }
  }

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
        onClick={() => void act(() => scheduling.generateRoster(span.from, span.to))}
      >
        Generate a draft for this span
      </button>

      <Failure error={failure} nameOf={nameOf} />
      <Failure error={rosters.error} />
      <Busy busy={rosters.busy} />

      <table>
        <thead>
          <tr>
            <th>Roster</th>
            <th>Status</th>
            <th>Assignments</th>
            <th>Approved under</th>
            <th>
              <span className="visually-hidden">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {(rosters.value ?? []).map((roster) => (
            <tr key={roster.id}>
              <td>
                {new Date(roster.start).toLocaleDateString()} to{' '}
                {new Date(roster.end).toLocaleDateString()}
                <br />
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
                    onClick={() => void act(() => scheduling.publishRoster(roster.id))}
                  >
                    Publish
                  </button>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {selected === null ? null : (
        <RosterBoard
          // Rebuilt for a different acting identity as well: what one identity was shown, and the
          // roster it had changed, must not stay on screen for another that may not read it.
          key={`${identityKey}#${selected}#${revision}`}
          roster={selected}
          people={people}
          onChanged={rosters.reload}
        />
      )}
    </section>
  );
}
