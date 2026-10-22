import { useState } from 'react';
import type { Violation } from '../api/problem';
import { scheduling } from '../api/scheduling';
import type { Employee } from '../api/workforce';
import { Busy, Failure, Violations } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { ApprovingRules } from './ApprovingRules';
import { RosterBoard } from './RosterBoard';

/**
 * Scheduler: generate a draft, change it by hand, publish it.
 *
 * Publication is the part that matters. A roster breaking the rules is refused with every violation
 * listed and there is no override, so the refusal is rendered inline as a first-class outcome
 * rather than as an error toast to dismiss.
 *
 * Generation fills every place the rules allow and only then weighs the objectives (ADR-044),
 * which this page says plainly rather than letting a scheduler assume more than that. It runs in the
 * background, for minutes at the largest size (ADR-046), so the page starts it and asks once a second
 * whether it has ended.
 *
 * The people come from the shell, which already loads them and already decides which refusal of
 * that listing to keep quiet about; asking again here doubled the request and repeated its failure.
 */
/** How long to wait between asking whether a generation has ended (ADR-046). */
const GENERATION_POLL_MS = 1000;

const pause = (milliseconds: number) =>
  new Promise<void>((resolve) => {
    setTimeout(resolve, milliseconds);
  });

/**
 * A generation that ended without a draft because work a published roster carries onto these
 * shifts can no longer stay there (#130). Told apart from a publication refusal, because what has to
 * change is that published roster, not this draft.
 */
class GenerationRefused extends Error {
  readonly violations: readonly Violation[];

  constructor(violations: readonly Violation[]) {
    super('Generation refused');
    this.name = 'GenerationRefused';
    this.violations = violations;
  }
}

export function RostersPage({
  span,
  people,
}: {
  span: { from: string; to: string };
  /** Everybody the acting role may list, to name assignees and offer on the board. */
  people: readonly Employee[];
}) {
  const sessionKey = useSessionKey();
  const [failure, setFailure] = useState<unknown>(null);
  const [selected, setSelected] = useState<string | null>(null);
  // The constraint version whose rules are open, so a published roster can be checked against the
  // rules it actually passed rather than today's (Q9).
  const [rulesOf, setRulesOf] = useState<number | null>(null);
  // Bumped whenever the list changes a roster, so an open board is rebuilt from what is now stored.
  // A change made on the board does not bump it: the board already holds what the server returned.
  const [revision, setRevision] = useState(0);
  const [generating, setGenerating] = useState(false);

  const rosters = useAsync(() => scheduling.findRosters(), sessionKey);
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

  /** Starts a generation and waits for it to end, refusing however it ended without a draft. */
  async function generate() {
    setGenerating(true);
    try {
      let generation = await scheduling.startGeneration(span.from, span.to);
      while (generation.status === 'PENDING') {
        await pause(GENERATION_POLL_MS);
        generation = await scheduling.findGeneration(generation.id);
      }
      if (generation.status === 'REFUSED') {
        throw new GenerationRefused(generation.violations);
      }
      if (generation.status === 'LOST') {
        throw new Error('The generation was lost: the server running it stopped. Generate again.');
      }
      if (generation.status === 'FAILED') {
        throw new Error('The generation failed. Generate again, and report it if it fails again.');
      }
    } finally {
      setGenerating(false);
    }
  }

  return (
    <section>
      <h2>Rosters</h2>
      <p className="hint">
        Generation fills as many places as the rules allow, then weighs fairness, preferences and
        disruption by your organisation&apos;s weights. It leaves a shift short rather than filling
        it illegally.
      </p>

      <button type="button" disabled={generating} onClick={() => void act(generate)}>
        Generate a draft for this span
      </button>
      <p className="busy" role="status">
        {generating ? 'Generating a draft…' : null}
      </p>

      {failure instanceof GenerationRefused ? (
        <div className="failure failure--rules" role="alert">
          <strong>
            Generation refused: a published roster carries people onto these shifts who can no
            longer stay on them.
          </strong>
          <p>Change that published roster first, then generate again.</p>
          <Violations violations={failure.violations} nameOf={nameOf} />
        </div>
      ) : (
        <Failure error={failure} nameOf={nameOf} />
      )}
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
          {(rosters.value ?? []).map((roster) => {
            const version = roster.validatedAgainstConstraintVersion;
            return (
              <tr key={roster.id}>
                <td>
                  {new Date(roster.start).toLocaleDateString()} to{' '}
                  {new Date(roster.end).toLocaleDateString()}
                  <br />
                  <code>{roster.id}</code>
                </td>
                <td>{roster.status}</td>
                <td>{roster.assignments.length}</td>
                <td>
                  {version === null ? (
                    '—'
                  ) : (
                    <button type="button" onClick={() => setRulesOf(version)}>
                      Version {version}
                    </button>
                  )}
                </td>
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
            );
          })}
        </tbody>
      </table>

      {rulesOf === null ? null : <ApprovingRules version={rulesOf} />}

      {selected === null ? null : (
        <RosterBoard
          // Rebuilt for a different acting identity as well: what one identity was shown, and the
          // roster it had changed, must not stay on screen for another that may not read it.
          key={`${sessionKey}#${selected}#${revision}`}
          roster={selected}
          people={people}
          onChanged={rosters.reload}
        />
      )}
    </section>
  );
}
