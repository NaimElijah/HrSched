import { useId, useState } from 'react';
import { identityKey } from '../api/client';
import { scheduling } from '../api/scheduling';
import type { Roster, Shift } from '../api/scheduling';
import { workforce } from '../api/workforce';
import type { Employee } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { dayLabel, localDate, shiftLabel, timeLabel } from '../components/localDates';
import { useAsync } from '../components/useAsync';
import { useActingAs } from '../identity/ActingAs';

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
  /** Called once a change is stored, so whatever lists rosters can catch up. */
  onChanged: () => void;
}) {
  const { identity } = useActingAs();
  const heading = useId();
  const [changed, setChanged] = useState<Roster | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  // One change at a time: two racing each other could leave the board showing the older answer.
  const [changing, setChanging] = useState(false);

  const loaded = useAsync(
    () => scheduling.findRoster(identity, id),
    `${identityKey(identity)}|${id}`,
  );
  const roster = changed ?? loaded.value;

  // Over the roster's own period, not the planning span: a roster opened while the span points
  // elsewhere would otherwise resolve none of its shifts.
  const shifts = useAsync(
    () =>
      roster === null
        ? Promise.resolve([])
        : scheduling.findShifts(identity, roster.start, roster.end),
    `${identityKey(identity)}|${roster?.start ?? ''}|${roster?.end ?? ''}`,
  );
  const qualifications = useAsync(
    () => workforce.findQualifications(identity),
    identityKey(identity),
  );

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
      onChanged();
      return true;
    } catch (error) {
      setFailure(error);
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
          {roster.assignments.length === 0 ? (
            <p>
              Nothing assigned. Either no shift falls in this span, or nobody eligible was available
              — an uncoverable shift is left short rather than filled illegally.
            </p>
          ) : null}
          <Failure error={failure} nameOf={nameOf} />

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
                      change(() => scheduling.assign(identity, roster.id, shift.id, employee))
                    }
                    onRemove={(employee) =>
                      change(() => scheduling.unassign(identity, roster.id, shift.id, employee))
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
