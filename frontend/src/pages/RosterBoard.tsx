import { useEffect, useId, useRef, useState, type RefObject } from 'react';
import { ApiError } from '../api/problem';
import { scheduling } from '../api/scheduling';
import type { Roster, Shift } from '../api/scheduling';
import { workforce } from '../api/workforce';
import type { Employee } from '../api/workforce';
import { Busy, Failure, Violations } from '../components/Feedback';
import { dayLabel, localDate, shiftLabel, timeLabel } from '../components/localDates';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { ObjectiveScores } from './ObjectiveScores';

/** One local day of the board, and the shifts that start on it, earliest first. */
interface Day {
  readonly day: string;
  readonly label: string;
  readonly shifts: readonly Shift[];
}

function byLocalDay(shifts: readonly Shift[]): Day[] {
  const earliestFirst = [...shifts].sort((a, b) => Date.parse(a.start) - Date.parse(b.start));
  const days = new Map<string, Day>();
  for (const shift of earliestFirst) {
    const day = localDate(shift.start);
    const known = days.get(day);
    days.set(day, {
      day,
      label: known?.label ?? dayLabel(shift.start),
      shifts: [...(known?.shifts ?? []), shift],
    });
  }
  return [...days.values()];
}

/**
 * Whether focus can be moved without taking it from anybody: it is still on the control that
 * acted, or it was dropped to the body when that control went.
 */
function focusIsUnclaimed(control: Element | null): boolean {
  const active = document.activeElement;
  return active === null || active === document.body || active === control;
}

/** How many shifts a draft leaves short, in words. */
function shortfallSummary(short: number): string {
  if (short === 0) {
    return 'Every shift has the people it needs.';
  }
  return short === 1
    ? '1 shift is short of people.'
    : `${String(short)} shifts are short of people.`;
}

/**
 * One roster, shift by shift: who is on each against how many it needs, and — while it is a draft —
 * the controls to change that by hand (requirement F2).
 *
 * The picker offers every employee. Working out who is qualified or available here would be a
 * second copy of the backend's rules, free to drift from them; the backend refuses an ineligible
 * assignment with its reason, and that refusal is what this shows. See ADR-025.
 *
 * It loads its own roster by identifier, and after a change shows the roster the server returned
 * for that change, rather than deriving what it shows from the roster list around it.
 *
 * A refusal of a change is shown in the shift it was about, and each change stored is said in a
 * status. It is opened from the rosters list, which moves focus to its heading and takes it back on
 * Close.
 */
export function RosterBoard({
  id: region,
  headingRef,
  roster: id,
  people,
  onChanged,
  onClose,
}: {
  /** The region's id, which the button that opened it names as what it controls. */
  id: string;
  /** The heading, focusable, for the opener to move focus to. */
  headingRef: RefObject<HTMLHeadingElement | null>;
  /** The identity of the roster to show. */
  roster: string;
  /** Everybody who might be assigned, and how to name them. */
  people: readonly Employee[];
  /**
   * Called whenever the stored roster may have moved on from what the list shows: a change stored
   * here, or one refused because somebody else changed the roster first. Either way, whatever lists
   * rosters should read them again.
   */
  onChanged: () => void;
  onClose: () => void;
}) {
  const sessionKey = useSessionKey();
  const heading = useId();
  const [changed, setChanged] = useState<Roster | null>(null);
  // A refused change, shown in the shift it was about.
  const [failure, setFailure] = useState<{ shift: string; error: unknown } | null>(null);
  // What the last change came to: stored, or refused by a conflict, which the board names because
  // the backend's detail names nobody.
  const [said, setSaid] = useState<string | null>(null);
  // One change at a time: two racing each other could leave the board showing the older answer.
  const [changing, setChanging] = useState(false);
  const checkHeading = useId();

  const loaded = useAsync(() => scheduling.findRoster(id), `${sessionKey}|${id}`);
  const roster = changed ?? loaded.value;
  const isDraft = roster?.status === 'DRAFT';

  // A draft that stops being one, because somebody published it first, takes its controls with it
  // and the focus that was on them. The heading is the nearest thing still there.
  const wasDraft = useRef(false);
  useEffect(() => {
    if (wasDraft.current && !isDraft && focusIsUnclaimed(null)) {
      headingRef.current?.focus();
    }
    wasDraft.current = isDraft;
  }, [isDraft, headingRef]);

  // Only a draft is checked: a published roster was judged when it was published (Q9). After an
  // edit the same question is asked again, by reload, so the last answer stays until the next.
  const assessment = useAsync(
    () => (isDraft ? scheduling.assessRoster(id) : Promise.resolve(null)),
    `${sessionKey}|${id}|${roster?.status ?? ''}`,
  );

  // Over the roster's own period, not the planning span: a roster opened while the span points
  // elsewhere would otherwise resolve none of its shifts.
  const shifts = useAsync(
    () => (roster === null ? Promise.resolve([]) : scheduling.findShifts(roster.start, roster.end)),
    `${sessionKey}|${roster?.start ?? ''}|${roster?.end ?? ''}`,
  );
  const qualifications = useAsync(() => workforce.findQualifications(), sessionKey);

  const period =
    roster === null
      ? ''
      : `${new Date(roster.start).toLocaleDateString()} to ${new Date(roster.end).toLocaleDateString()}`;
  const qualificationNames = new Map(
    (qualifications.value ?? []).map((qualification) => [qualification.id, qualification.name]),
  );
  const personById = new Map(people.map((person) => [person.id, person]));
  const nameOf = (employee: string): string => personById.get(employee)?.name ?? employee;

  // Who is assigned is the roster's to say, not the shift listing's. When a shift cannot be placed —
  // its listing failed, or does not include it — the assignment is still shown, by identifier.
  const placed = new Set((shifts.value ?? []).map((shift) => shift.id));
  const unplaced =
    roster === null || shifts.busy
      ? []
      : roster.assignments.filter((assignment) => !placed.has(assignment.shift));

  /**
   * @param shift the shift the change is about, where a refusal of it is shown
   * @param attempted what the change was, as "assign Grace to …", for the sentence a conflict gets
   * @param stored what it came to, as "Assigned Grace to …", for the status once it is stored
   * @returns whether the change was stored, so a control can reset only on success
   */
  async function change(
    shift: string,
    action: () => Promise<Roster>,
    attempted: string,
    stored: string,
  ): Promise<boolean> {
    if (changing) {
      return false;
    }
    setChanging(true);
    setFailure(null);
    setSaid(null);
    try {
      setChanged(await action());
      setSaid(`${stored}.`);
      assessment.reload();
      onChanged();
      return true;
    } catch (error) {
      setFailure({ shift, error });
      // A conflict means the roster moved on under this board, most often because somebody
      // published it first (ADR-041). Shown as it now is, so a draft's controls are not left on a
      // roster that is no longer one, and the list around it catches up too. What the check said
      // was said of the roster as it was, so it is asked again.
      if (error instanceof ApiError && error.isConflict) {
        setSaid(
          `Could not ${attempted}. The board was read again, and shows the roster as it now is.`,
        );
        setChanged(null);
        loaded.reload();
        assessment.reload();
        onChanged();
      }
      return false;
    } finally {
      setChanging(false);
    }
  }

  return (
    <section id={region} className="detail" aria-labelledby={heading}>
      <h3 id={heading} ref={headingRef} tabIndex={-1}>
        {roster === null ? 'Roster' : `Roster ${roster.status.toLowerCase()}: ${period}`}
      </h3>
      <button
        type="button"
        aria-label={roster === null ? 'Close roster' : `Close roster ${period}`}
        onClick={onClose}
      >
        Close
      </button>
      <Failure error={loaded.error} />
      <Failure error={shifts.error} />
      <Failure error={qualifications.error} />
      <Busy busy={roster === null ? loaded.busy : shifts.busy} />

      {roster === null ? null : (
        <>
          <p className="hint">
            {roster.status === 'DRAFT'
              ? "A draft can be changed by hand. Whether somebody may work a shift is still the backend's call: an assignment it will not accept is refused, with its reason."
              : 'Only a draft is changed by hand. A published roster is replaced by publishing another, never edited.'}
          </p>
          {/* No guess at why a draft is empty: it may have been generated so, or emptied by hand.
              Each shift states its own shortfall; only a period with no shifts at all, which would
              otherwise leave the board blank, needs saying. */}
          {roster.assignments.length === 0 && shifts.value?.length === 0 ? (
            <p>No shift falls in this roster&apos;s period, so there is nothing to assign.</p>
          ) : null}
          {/* Present while empty, as a live region must be for what appears in it to be read. */}
          <p className="hint" role="status">
            {said}
          </p>

          {isDraft ? (
            <section className="check" aria-labelledby={checkHeading}>
              <h4 id={checkHeading}>Checked against the rules</h4>
              <Failure error={assessment.error} nameOf={nameOf} />
              {assessment.value === null ? null : (
                <>
                  {assessment.value.violations.length === 0 ? (
                    <p>No rule is broken.</p>
                  ) : (
                    <Violations violations={assessment.value.violations} nameOf={nameOf} />
                  )}
                  <p>{shortfallSummary(assessment.value.shortfalls.length)}</p>
                  <ObjectiveScores
                    objectives={assessment.value.objectives}
                    weightedTotal={assessment.value.weightedTotal}
                  />
                </>
              )}
              <p className="hint">
                Publication still decides: this is what it would have said a moment ago.
              </p>
            </section>
          ) : null}

          {byLocalDay(shifts.value ?? []).map((day) => (
            <div key={day.day} className="day">
              <h4>{day.label}</h4>
              <ul className="shifts">
                {day.shifts.map((shift) => (
                  <ShiftOnBoard
                    key={shift.id}
                    shift={shift}
                    assigned={roster.assignments
                      .filter((assignment) => assignment.shift === shift.id)
                      .map((assignment) => assignment.employee)}
                    editable={roster.status === 'DRAFT'}
                    requires={
                      shift.requiredQualifications.length === 0
                        ? 'No qualification required'
                        : `Requires ${shift.requiredQualifications
                            .map(
                              (qualification) =>
                                qualificationNames.get(qualification) ?? qualification,
                            )
                            .join(', ')}`
                    }
                    people={people}
                    nameOf={nameOf}
                    busy={changing}
                    failure={failure?.shift === shift.id ? failure.error : null}
                    onAssign={(employee) =>
                      change(
                        shift.id,
                        () => scheduling.assign(roster.id, shift.id, employee),
                        `assign ${nameOf(employee)} to ${shiftLabel(shift.start, shift.end)}`,
                        `Assigned ${nameOf(employee)} to ${shiftLabel(shift.start, shift.end)}`,
                      )
                    }
                    onRemove={(employee) =>
                      change(
                        shift.id,
                        () => scheduling.unassign(roster.id, shift.id, employee),
                        `remove ${nameOf(employee)} from ${shiftLabel(shift.start, shift.end)}`,
                        `Removed ${nameOf(employee)} from ${shiftLabel(shift.start, shift.end)}`,
                      )
                    }
                  />
                ))}
              </ul>
            </div>
          ))}

          {unplaced.length === 0 ? null : (
            <div className="day">
              <h4>On shifts that could not be shown</h4>
              <ul className="assignees">
                {unplaced.map((assignment) => (
                  <li key={`${assignment.shift}|${assignment.employee}`}>
                    <span>{nameOf(assignment.employee)}</span> — shift{' '}
                    <code>{assignment.shift}</code>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </section>
  );
}

function ShiftOnBoard({
  shift,
  assigned,
  editable,
  requires,
  people,
  nameOf,
  busy,
  failure,
  onAssign,
  onRemove,
}: {
  shift: Shift;
  /** The employees on this shift, in the order the roster lists them. */
  assigned: readonly string[];
  editable: boolean;
  requires: string;
  people: readonly Employee[];
  nameOf: (employee: string) => string;
  /**
   * A change is on its way somewhere on the board, so these controls wait for its answer. They are
   * aria-disabled rather than disabled, so the one pressed keeps the keyboard focus.
   */
  busy: boolean;
  /** Why the last change to this shift was refused, if it was. */
  failure: unknown;
  onAssign: (employee: string) => Promise<boolean>;
  onRemove: (employee: string) => Promise<boolean>;
}) {
  const [choice, setChoice] = useState('');
  const picker = useRef<HTMLSelectElement>(null);
  const times = `${timeLabel(shift.start)} to ${timeLabel(shift.end)}`;
  // A board repeats the same controls on every shift, so each control's name says which one it is.
  const when = shiftLabel(shift.start, shift.end);
  const shortfall = shift.requiredHeadcount - assigned.length;

  return (
    <li className="shift">
      <p>
        <strong>{times}</strong>
      </p>
      <p>{requires}</p>
      <p className={shortfall > 0 ? 'short' : undefined}>
        {`${assigned.length} of ${shift.requiredHeadcount} assigned${shortfall > 0 ? ` — ${shortfall} short` : ''}`}
      </p>
      {assigned.length === 0 ? null : (
        <ul className="assignees">
          {assigned.map((employee) => (
            <li key={employee}>
              <span>{nameOf(employee)}</span>
              {editable ? (
                <>
                  {' '}
                  <button
                    type="button"
                    aria-disabled={busy}
                    aria-label={`Remove ${nameOf(employee)} from ${when}`}
                    onClick={(event) => {
                      if (busy) {
                        return;
                      }
                      const pressed = event.currentTarget;
                      // Remove goes with the person it removed, so focus moves to where the next
                      // one is chosen.
                      void onRemove(employee).then((removed) => {
                        if (removed && focusIsUnclaimed(pressed)) {
                          picker.current?.focus();
                        }
                      });
                    }}
                  >
                    Remove
                  </button>
                </>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {editable ? (
        <form
          className="row"
          onSubmit={(event) => {
            event.preventDefault();
            if (busy) {
              return;
            }
            void onAssign(choice).then((assigned) => {
              if (assigned) {
                setChoice('');
              }
            });
          }}
        >
          <select
            ref={picker}
            value={choice}
            required
            aria-disabled={busy}
            aria-label={`Who to assign to ${when}`}
            onChange={(event) => {
              if (!busy) {
                setChoice(event.target.value);
              }
            }}
          >
            <option value="">choose…</option>
            {people.map((person) => (
              <option key={person.id} value={person.id}>
                {person.name}
              </option>
            ))}
          </select>
          <button type="submit" aria-disabled={busy} aria-label={`Assign to ${when}`}>
            Assign
          </button>
        </form>
      ) : null}
      <Failure error={failure} nameOf={nameOf} />
    </li>
  );
}
