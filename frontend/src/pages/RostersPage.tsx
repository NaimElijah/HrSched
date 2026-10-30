import { useEffect, useRef, useState } from 'react';
import { ApiError, type Violation } from '../api/problem';
import { scheduling, type Generation } from '../api/scheduling';
import type { Employee } from '../api/workforce';
import { Busy, Failure, Violations } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { ApprovingRules } from './ApprovingRules';
import { RosterBoard } from './RosterBoard';

/** How long to wait between asking whether a generation has ended (ADR-046). */
const GENERATION_POLL_MS = 1000;

/**
 * Whether a failure to ask how a generation stands may pass: the network failing, which rejects
 * rather than answering, or a server error, which is what a gateway answers while the server behind
 * it is replaced. Anything the server refused is an answer, and asking again would get it again.
 */
const passing = (error: unknown): boolean => !(error instanceof ApiError) || error.status >= 500;

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
export function RostersPage({
  span,
  people,
  waitingFor,
  onWaitingFor,
}: {
  span: { from: string; to: string };
  /** Everybody the acting role may list, to name assignees and offer on the board. */
  people: readonly Employee[];
  /**
   * The generation started here and not yet seen to end. Held by the shell rather than by this
   * page, which is gone whenever another tab is open, so that coming back keeps waiting for it
   * instead of offering to start a second solve.
   */
  waitingFor: string | null;
  onWaitingFor: (generation: string | null) => void;
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
  const [starting, setStarting] = useState(false);
  // A second publication sent while the first is on its way would be refused as a conflict, and
  // that refusal shown beside the first one's success.
  const [publishing, setPublishing] = useState(false);
  const generating = starting || waitingFor !== null;
  // Why asking how the generation stands last failed, while it is still being asked.
  const [askingFailed, setAskingFailed] = useState<unknown>(null);

  const rosters = useAsync(() => scheduling.findRosters(), sessionKey);
  const personById = new Map(people.map((person) => [person.id, person]));
  const nameOf = (employee: string): string | undefined => personById.get(employee)?.name;

  async function publish(roster: string) {
    if (publishing) {
      return;
    }
    setFailure(null);
    setPublishing(true);
    try {
      await scheduling.publishRoster(roster);
      rosters.reload();
      setRevision((previous) => previous + 1);
    } catch (error) {
      setFailure(error);
    } finally {
      setPublishing(false);
    }
  }

  /**
   * Shows how a generation ended, refusing however it ended without a draft. An ending this build
   * has never heard of is named as such: waiting has stopped, and stopping without a word would
   * leave a Scheduler waiting for a draft that may never come.
   */
  function ended(generation: Generation) {
    onWaitingFor(null);
    if (generation.status === 'SUCCEEDED') {
      rosters.reload();
      setRevision((previous) => previous + 1);
      // Waited for, often for minutes, so it opens rather than waiting to be found in the list.
      setSelected(generation.roster);
    } else if (generation.status === 'REFUSED') {
      setFailure(new GenerationRefused(generation.violations));
    } else if (generation.status === 'LOST') {
      setFailure(
        new Error('The generation was lost: the server running it stopped. Generate again.'),
      );
    } else if (generation.status === 'FAILED') {
      setFailure(
        new Error('The generation failed. Generate again, and report it if it fails again.'),
      );
    } else {
      rosters.reload();
      setFailure(
        new Error(
          `The generation ended as ${generation.status}, which this version of HrSched cannot describe. Check the rosters below for its draft before generating again.`,
        ),
      );
    }
  }

  // Read by the wait below, which outlives the render it began in.
  const end = useRef(ended);
  const stopWaiting = useRef(onWaitingFor);
  useEffect(() => {
    end.current = ended;
    stopWaiting.current = onWaitingFor;
  });

  // Asks once a second until the generation ends, for as long as this page is open. Leaving stops
  // the asking but not the waiting: the shell still holds the generation, so coming back resumes.
  // The network or a server being replaced failing for a moment is asked past, since the solve
  // carries on elsewhere; any other refusal ends the wait.
  useEffect(() => {
    if (waitingFor === null) {
      return undefined;
    }
    let live = true;
    const wait = async () => {
      for (;;) {
        await pause(GENERATION_POLL_MS);
        if (!live) {
          return;
        }
        let generation: Generation;
        try {
          generation = await scheduling.findGeneration(waitingFor);
        } catch (error) {
          if (!passing(error)) {
            throw error;
          }
          if (live) {
            setAskingFailed(error);
          }
          continue;
        }
        if (!live) {
          return;
        }
        setAskingFailed(null);
        if (generation.status !== 'PENDING') {
          end.current(generation);
          return;
        }
      }
    };
    wait().catch((error: unknown) => {
      if (live) {
        stopWaiting.current(null);
        setAskingFailed(null);
        setFailure(error);
      }
    });
    return () => {
      live = false;
    };
  }, [waitingFor]);

  /** Starts a generation, then waits for it to end. */
  async function generate() {
    setFailure(null);
    setStarting(true);
    try {
      const started = await scheduling.startGeneration(span.from, span.to);
      if (started.status === 'PENDING') {
        onWaitingFor(started.id);
      } else {
        ended(started);
      }
    } catch (error) {
      setFailure(error);
    } finally {
      setStarting(false);
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

      <button type="button" disabled={generating} onClick={() => void generate()}>
        Generate a draft for this span
      </button>
      <p className="busy" role="status">
        {generating ? 'Generating a draft…' : null}
        {generating && askingFailed !== null
          ? ' Asking how it stands failed, so asking again.'
          : null}
      </p>
      {generating ? <Failure error={askingFailed} /> : null}

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
                      disabled={publishing}
                      onClick={() => void publish(roster.id)}
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
