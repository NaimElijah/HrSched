import { Fragment, useEffect, useId, useRef, useState } from 'react';
import { ApiError, type Violation } from '../api/problem';
import {
  isGenerationStatus,
  scheduling,
  type Generation,
  type GenerationStatus,
  type Roster,
} from '../api/scheduling';
import type { Employee } from '../api/workforce';
import { Busy, Failure, Violations } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { ApprovingRules } from './ApprovingRules';
import { RosterBoard } from './RosterBoard';

/** How a generation stands while it is still running, typed so that a misspelling fails to compile. */
const PENDING: GenerationStatus = 'PENDING';

/** How long to wait between asking whether a generation has ended (ADR-046). */
const GENERATION_POLL_MS = 1000;

/**
 * Whether a failure to ask how a generation stands may pass: the network failing, which rejects
 * rather than answering, or a server error, which is what a gateway answers while the server behind
 * it is replaced. Anything the server refused is an answer, and asking again would get it again.
 */
const passing = (error: unknown): boolean => !(error instanceof ApiError) || error.status >= 500;

/** The rosters table's columns, which a row spanning all of them has to know. */
const ROSTER_COLUMNS = 5;

/** How the page names a roster: its period, in local dates, as its row shows it. */
function labelOf(roster: { start: string; end: string }): string {
  return `${new Date(roster.start).toLocaleDateString()} to ${new Date(roster.end).toLocaleDateString()}`;
}

/**
 * Whether focus can be moved without taking it from anybody: it is still on the control that
 * acted, or it was dropped to the body when that control went.
 */
function focusIsUnclaimed(control: Element | null): boolean {
  const active = document.activeElement;
  return active === null || active === document.body || active === control;
}

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
  // A publication refused, shown at the row of the roster it refused rather than above the table.
  const [refused, setRefused] = useState<{ roster: string; error: unknown } | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  // The constraint version whose rules are open, so a published roster can be checked against the
  // rules it actually passed rather than today's (Q9), and the roster whose button opened them.
  const [rulesOf, setRulesOf] = useState<{ version: number; roster: string } | null>(null);
  // Bumped whenever the list changes a roster, so an open board is rebuilt from what is now stored.
  // A change made on the board does not bump it: the board already holds what the server returned.
  const [revision, setRevision] = useState(0);
  const [starting, setStarting] = useState(false);
  // The roster being published. A second publication sent while the first is on its way would be
  // refused as a conflict, and that refusal shown beside the first one's success.
  const [publishing, setPublishing] = useState<string | null>(null);
  const generating = starting || waitingFor !== null;
  // Why asking how the generation stands last failed, while it is still being asked.
  const [askingFailed, setAskingFailed] = useState<unknown>(null);
  const [published, setPublished] = useState<string | null>(null);
  // The draft a generation made, named by the status once the list read again includes it.
  const [ready, setReady] = useState<string | null>(null);
  // A new object for every request, so asking for the same heading twice still moves focus.
  const [focusing, setFocusing] = useState<{ readonly on: 'board' | 'rules' } | null>(null);

  const generateButton = useRef<HTMLButtonElement>(null);
  const boardHeading = useRef<HTMLHeadingElement>(null);
  const rulesHeading = useRef<HTMLHeadingElement>(null);
  // Each row's openers and header cell, by `open:`, `rules:` or `row:` and the roster's id, for
  // focus to return to or move to.
  const controls = useRef(new Map<string, HTMLElement>());
  const remember = (key: string) => (element: HTMLElement | null) => {
    if (element === null) {
      controls.current.delete(key);
    } else {
      controls.current.set(key, element);
    }
  };

  const board = useId();
  const rules = useId();
  const caption = useId();
  const publishingHint = useId();

  const rosters = useAsync(() => scheduling.findRosters(), sessionKey);
  const personById = new Map(people.map((person) => [person.id, person]));
  const nameOf = (employee: string): string | undefined => personById.get(employee)?.name;

  useEffect(() => {
    if (focusing !== null) {
      (focusing.on === 'board' ? boardHeading : rulesHeading).current?.focus();
    }
  }, [focusing]);

  const readyRoster = rosters.value?.find((roster) => roster.id === ready);
  // Said once the list read again names the draft, rather than twice, first without its period.
  const readySaying =
    ready === null
      ? null
      : readyRoster !== undefined
        ? `Draft ready: ${labelOf(readyRoster)}.`
        : rosters.busy
          ? null
          : 'Draft ready.';

  function open(roster: string) {
    if (refused?.roster !== roster) {
      setRefused(null);
    }
    setSelected(roster);
    setFocusing({ on: 'board' });
  }

  /** Closes the board, handing focus back to what opened it: its row's Open, or Generate. */
  function closeBoard() {
    const opener = selected === null ? undefined : controls.current.get(`open:${selected}`);
    (opener ?? generateButton.current)?.focus();
    setSelected(null);
  }

  function closeRules() {
    if (rulesOf !== null) {
      controls.current.get(`rules:${rulesOf.roster}`)?.focus();
    }
    setRulesOf(null);
  }

  /** @param control the Publish pressed, which goes once the list shows the roster published */
  async function publish(roster: Roster, control: HTMLElement) {
    if (publishing !== null) {
      return;
    }
    setFailure(null);
    setRefused(null);
    setPublished(null);
    setReady(null);
    setPublishing(roster.id);
    try {
      await scheduling.publishRoster(roster.id);
      setPublished(`Published ${labelOf(roster)}.`);
      if (focusIsUnclaimed(control)) {
        controls.current.get(`row:${roster.id}`)?.focus();
      }
      rosters.reload();
      setRevision((previous) => previous + 1);
    } catch (error) {
      setRefused({ roster: roster.id, error });
    } finally {
      setPublishing(null);
    }
  }

  /**
   * Shows how a generation ended, refusing however it ended without a draft. An ending this build
   * has never heard of is named as such: waiting has stopped, and stopping without a word would
   * leave a Scheduler waiting for a draft that may never come.
   */
  function ended(generation: Generation) {
    onWaitingFor(null);
    const status = generation.status;
    if (!isGenerationStatus(status)) {
      rosters.reload();
      setFailure(
        new Error(
          `The generation ended as ${status}, which this version of HrSched cannot describe. Check the rosters below for its draft before generating again.`,
        ),
      );
      return;
    }
    if (status === 'SUCCEEDED') {
      rosters.reload();
      setRevision((previous) => previous + 1);
      // Waited for, often for minutes, so it opens rather than waiting to be found in the list. Focus
      // follows it only if it is still on Generate: whoever moved on meanwhile is left where they are.
      setSelected(generation.roster);
      setReady(generation.roster);
      if (focusIsUnclaimed(generateButton.current)) {
        setFocusing({ on: 'board' });
      }
    } else if (status === 'REFUSED') {
      setFailure(new GenerationRefused(generation.violations));
    } else if (status === 'LOST') {
      setFailure(
        new Error('The generation was lost: the server running it stopped. Generate again.'),
      );
    } else if (status === 'FAILED') {
      setFailure(
        new Error('The generation failed. Generate again, and report it if it fails again.'),
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
        if (generation.status !== PENDING) {
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
    if (generating) {
      return;
    }
    setFailure(null);
    setRefused(null);
    setPublished(null);
    setReady(null);
    setStarting(true);
    try {
      const started = await scheduling.startGeneration(span.from, span.to);
      if (started.status === PENDING) {
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

      {/* aria-disabled rather than disabled, so focus stays on the button while a generation is out. */}
      <button
        ref={generateButton}
        type="button"
        aria-disabled={generating}
        onClick={() => void generate()}
      >
        Generate a draft for this span
      </button>
      <p className="busy" role="status">
        {generating ? 'Generating a draft…' : null}
        {generating && askingFailed !== null
          ? ' Asking how it stands failed, so asking again.'
          : null}
      </p>
      {generating ? <Failure error={askingFailed} /> : null}
      {/* Always in the page, so an outcome is announced when it arrives rather than skipped. */}
      <p className="done" role="status">
        {published ?? readySaying}
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
      {rosters.value?.length === 0 ? <p>No rosters yet.</p> : null}
      {publishing === null ? null : (
        <p className="hint" id={publishingHint}>
          Another roster is being published. Publishing waits for its answer.
        </p>
      )}

      <div className="table-scroll" role="region" aria-labelledby={caption} tabIndex={0}>
        <table>
          {/* Hidden because the heading above already says it; the table still needs a name. */}
          <caption id={caption} className="visually-hidden">
            Rosters
          </caption>
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
              const label = labelOf(roster);
              return (
                <Fragment key={roster.id}>
                  <tr>
                    {/* Focusable, so focus has somewhere to go when this row's Publish goes. */}
                    <th scope="row" tabIndex={-1} ref={remember(`row:${roster.id}`)}>
                      {label}
                      <br />
                      <code>{roster.id}</code>
                    </th>
                    <td>{roster.status}</td>
                    <td>{roster.assignments.length}</td>
                    <td>
                      {version === null ? (
                        '—'
                      ) : (
                        <button
                          ref={remember(`rules:${roster.id}`)}
                          type="button"
                          aria-label={`Version ${String(version)}: the rules that approved roster ${label}`}
                          aria-expanded={rulesOf?.roster === roster.id}
                          aria-controls={rules}
                          onClick={() => {
                            setRulesOf({ version, roster: roster.id });
                            setFocusing({ on: 'rules' });
                          }}
                        >
                          Version {version}
                        </button>
                      )}
                    </td>
                    <td>
                      <button
                        ref={remember(`open:${roster.id}`)}
                        type="button"
                        aria-label={`Open roster ${label}`}
                        aria-expanded={selected === roster.id}
                        aria-controls={board}
                        onClick={() => open(roster.id)}
                      >
                        Open
                      </button>
                      {roster.status === 'DRAFT' ? (
                        <button
                          type="button"
                          aria-label={`Publish roster ${label}`}
                          aria-disabled={publishing !== null}
                          aria-describedby={
                            publishing === null || publishing === roster.id
                              ? undefined
                              : publishingHint
                          }
                          onClick={(event) => void publish(roster, event.currentTarget)}
                        >
                          Publish
                        </button>
                      ) : null}
                    </td>
                  </tr>
                  {refused?.roster === roster.id ? (
                    <tr>
                      <td colSpan={ROSTER_COLUMNS}>
                        <Failure error={refused.error} nameOf={nameOf} />
                      </td>
                    </tr>
                  ) : null}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>

      {rulesOf === null ? null : (
        <ApprovingRules
          id={rules}
          headingRef={rulesHeading}
          version={rulesOf.version}
          onClose={closeRules}
        />
      )}

      {selected === null ? null : (
        <RosterBoard
          // Rebuilt for a different acting identity as well: what one identity was shown, and the
          // roster it had changed, must not stay on screen for another that may not read it.
          key={`${sessionKey}#${selected}#${revision}`}
          id={board}
          headingRef={boardHeading}
          roster={selected}
          people={people}
          onChanged={rosters.reload}
          onClose={closeBoard}
        />
      )}
    </section>
  );
}
