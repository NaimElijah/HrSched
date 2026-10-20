import { useId, useState } from 'react';
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
 */
export function RosterBoard({
  roster: id,
  people,
  onChanged,
}: {
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
}) {
  const sessionKey = useSessionKey();
  const heading = useId();
  const [changed, setChanged] = useState<Roster | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  // One change at a time: two racing each other could leave the board showing the older answer.
  const [changing, setChanging] = useState(false);
  const checkHeading = useId();

  const loaded = useAsync(() => scheduling.findRoster(id), `${sessionKey}|${id}`);
  const roster = changed ?? loaded.value;
  const isDraft = roster?.status === 'DRAFT';

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

  /** @returns whether the change was stored, so a control can reset only on success */
  async function change(action: () => Promise<Roster>): Promise<boolean> {
    if (changing) {
      return false;
    }
    setChanging(true);
    setFailure(null);
    try {
      setChanged(await action());
      assessment.reload();
      onChanged();
      return true;
    } catch (error) {
      setFailure(error);
      // A conflict means the roster moved on under this board, most often because somebody
      // published it first (ADR-041). Shown as it now is, so a draft's controls are not left on a
      // roster that is no longer one, and the list around it catches up too.
      if (error instanceof ApiError && error.isConflict) {
        setChanged(null);
        loaded.reload();
        onChanged();
      }
      return false;
    } finally {
      setChanging(false);
    }
  }

  return (
    <section className="detail" aria-labelledby={heading}>
      <h3 id={heading}>
        {roster === null
          ? 'Roster'
          : `Roster ${roster.status.toLowerCase()}: ${new Date(roster.start).toLocaleDateString()} to ${new Date(roster.end).toLocaleDateString()}`}
      </h3>
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
          <Failure error={failure} nameOf={nameOf} />

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
                    onAssign={(employee) =>
                      change(() => scheduling.assign(roster.id, shift.id, employee))
                    }
                    onRemove={(employee) =>
                      change(() => scheduling.unassign(roster.id, shift.id, employee))
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
  /** A change is on its way somewhere on the board, so these controls wait for its answer. */
  busy: boolean;
  onAssign: (employee: string) => Promise<boolean>;
  onRemove: (employee: string) => Promise<boolean>;
}) {
  const [choice, setChoice] = useState('');
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
                    disabled={busy}
                    aria-label={`Remove ${nameOf(employee)} from ${when}`}
                    onClick={() => void onRemove(employee)}
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
            void onAssign(choice).then((assigned) => {
              if (assigned) {
                setChoice('');
              }
            });
          }}
        >
          <select
            value={choice}
            required
            disabled={busy}
            aria-label={`Who to assign to ${when}`}
            onChange={(event) => setChoice(event.target.value)}
          >
            <option value="">choose…</option>
            {people.map((person) => (
              <option key={person.id} value={person.id}>
                {person.name}
              </option>
            ))}
          </select>
          <button type="submit" disabled={busy} aria-label={`Assign to ${when}`}>
            Assign
          </button>
        </form>
      ) : null}
    </li>
  );
}
