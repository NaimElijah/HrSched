import { Fragment, useId, useState } from 'react';
import { workforce } from '../api/workforce';
import type { Qualification } from '../api/workforce';
import { Busy, Failure, isAboutTheValue } from '../components/Feedback';
import { RowRefusal } from '../components/RowRefusal';
import { TableRegion } from '../components/TableRegion';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';

/** How many columns the people table has, so a row's refusal can span all of them. */
const COLUMNS = 5;

/** The control in a person's row that a refusal of its change is about. */
type RowField = 'grant' | 'contract';

/** A refusal of one row's change, and the control it marks if it is about that one's value (C6). */
interface RefusedChange {
  readonly error: unknown;
  readonly field: RowField | null;
}

/**
 * Admin: who works here, what they hold, and what they are contracted to.
 *
 * Nobody added can be removed, so an addition is sent once: the button waits for the answer. Nor
 * can a qualification be revoked yet (F24, #86), so choosing one grants nothing until it is
 * submitted.
 */
export function PeoplePage() {
  const sessionKey = useSessionKey();
  const [name, setName] = useState('');
  const [failure, setFailure] = useState<unknown>(null);
  // What the last change did, whichever form made it: an addition, a grant or a contract. Nothing
  // else confirms one where the Admin is looking. An added person may sort anywhere in the table,
  // a granted qualification's picker empties, and a contract's field keeps what was typed whether
  // or not it was stored.
  const [done, setDone] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  // Each row's own refusal, by employee id, shown in that row rather than at the top of the page,
  // where it would say nothing about whose it was.
  const [refusals, setRefusals] = useState<ReadonlyMap<string, RefusedChange>>(new Map());
  // One prefix for the ids each row derives from its employee: the name header a refusal's cell
  // names as its header, and the refusal the refused control is described by.
  const rowIds = useId();
  const headerOf = (employee: string) => `${rowIds}-header-${employee}`;
  const refusalOf = (employee: string) => `${rowIds}-refusal-${employee}`;

  const people = useAsync(() => workforce.findEmployees(), sessionKey);
  const qualifications = useAsync(() => workforce.findQualifications(), sessionKey);
  const qualificationNames = new Map(
    (qualifications.value ?? []).map((qualification) => [qualification.id, qualification.name]),
  );

  function refuse(row: string, refusal: RefusedChange) {
    setRefusals((previous) => new Map(previous).set(row, refusal));
  }

  /** The id describing this row's control while a refusal is about it; otherwise none. */
  function refusedBy(employee: string, field: RowField): string | undefined {
    return refusals.get(employee)?.field === field ? refusalOf(employee) : undefined;
  }

  function forgetRefusal(row: string) {
    setRefusals((previous) => {
      const next = new Map(previous);
      next.delete(row);
      return next;
    });
  }

  /**
   * @param said what to say once the action has succeeded
   * @param row the employee whose row acted and the control that acted, whose refusal is shown in
   *   that row and marks that control; none for the add form
   * @returns whether the action succeeded
   */
  async function act(
    action: () => Promise<unknown>,
    said: string,
    row?: { employee: string; field: RowField },
  ): Promise<boolean> {
    setDone(null);
    // A row's action leaves the add form's refusal alone: the refused name is still typed there.
    if (row === undefined) {
      setFailure(null);
    } else {
      forgetRefusal(row.employee);
    }
    try {
      await action();
      setDone(said);
      people.reload();
      return true;
    } catch (error) {
      if (row === undefined) {
        setFailure(error);
      } else {
        refuse(row.employee, { error, field: isAboutTheValue(error) ? row.field : null });
      }
      return false;
    }
  }

  return (
    <section>
      <h2>People</h2>
      <p className="hint">
        Listing people returns their unavailability, which is personal data, so it takes an Admin or
        a Scheduler. An account holding the Employee role is refused this list.
      </p>

      {/* Change events bubble, so any edit clears a success that would read as the next one's. */}
      <form
        className="row"
        onChange={() => setDone(null)}
        onSubmit={(event) => {
          event.preventDefault();
          if (adding) {
            return;
          }
          setAdding(true);
          void act(async () => {
            await workforce.addEmployee(name);
            setName('');
          }, `Added ${name}.`).finally(() => setAdding(false));
        }}
      >
        <label>
          Name
          <input value={name} onChange={(event) => setName(event.target.value)} required />
        </label>
        {/* aria-disabled rather than disabled, so focus stays on the button while its request is
            on its way. */}
        <button type="submit" aria-disabled={adding}>
          Add employee
        </button>
      </form>

      <Failure error={failure} />
      <p className="done" role="status">
        {done}
      </p>
      <Failure error={people.error} />
      <Busy busy={people.busy} />

      {/* Only an answer says who works here: while loading, or after a refusal, there is no list
          to show, and an empty table would read as nobody. */}
      {people.value === null ? null : people.value.length === 0 ? (
        <p>No people yet.</p>
      ) : (
        <TableRegion caption="People in this organisation">
          <thead>
            <tr>
              <th>Name</th>
              <th>Qualifications</th>
              <th>Cannot work</th>
              <th>Grant</th>
              <th>Contracted minutes / week</th>
            </tr>
          </thead>
          <tbody>
            {people.value.map((employee) => {
              const refusal = refusals.get(employee.id);
              return (
                <Fragment key={employee.id}>
                  <tr>
                    <th scope="row" id={headerOf(employee.id)}>
                      {employee.name}
                      <br />
                      <code>{employee.id}</code>
                    </th>
                    <td>
                      {employee.qualifications.length === 0
                        ? '—'
                        : employee.qualifications
                            .map((id) => qualificationNames.get(id) ?? id)
                            .join(', ')}
                    </td>
                    <td>
                      {employee.unavailability.length === 0 ? (
                        '—'
                      ) : (
                        <ul className="spans">
                          {employee.unavailability.map((period) => (
                            <li key={`${period.start}|${period.end}`}>
                              {new Date(period.start).toLocaleString()} to{' '}
                              {new Date(period.end).toLocaleString()}
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                    <td>
                      <GrantField
                        employee={employee.name}
                        identifier={employee.id}
                        qualifications={qualifications.value ?? []}
                        refusedBy={refusedBy(employee.id, 'grant')}
                        onEdit={() => setDone(null)}
                        onSubmit={(qualification) =>
                          act(
                            () => workforce.grantQualification(employee.id, qualification),
                            `Granted ${qualificationNames.get(qualification) ?? qualification} to ${employee.name}.`,
                            { employee: employee.id, field: 'grant' },
                          )
                        }
                      />
                    </td>
                    <td>
                      {/* Beside the field, never in it: a prefilled number could be submitted unchosen. */}
                      <p className="stored">
                        {employee.weeklyContractedMinutes === null
                          ? 'None set'
                          : `${employee.weeklyContractedMinutes} minutes a week`}
                      </p>
                      <ContractField
                        employee={employee.name}
                        refusedBy={refusedBy(employee.id, 'contract')}
                        onEdit={() => setDone(null)}
                        onSubmit={(minutes) =>
                          act(
                            () => workforce.setContract(employee.id, minutes),
                            `Set contracted minutes for ${employee.name} to ${String(minutes)} a week.`,
                            { employee: employee.id, field: 'contract' },
                          )
                        }
                      />
                    </td>
                  </tr>
                  {refusal === undefined ? null : (
                    <RowRefusal
                      error={refusal.error}
                      id={refusalOf(employee.id)}
                      headers={headerOf(employee.id)}
                      columns={COLUMNS}
                    />
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </TableRegion>
      )}
    </section>
  );
}

/**
 * A qualification to grant, and the button that grants it.
 *
 * A write on choosing would be one nobody can undo, and with the keyboard a closed select changes
 * on every arrow key in some browsers: an arrow pressed past the wanted option would grant another.
 */
function GrantField({
  employee,
  identifier,
  qualifications,
  refusedBy,
  onEdit,
  onSubmit,
}: {
  /** Whose field it is, so every row's controls have a name of their own. */
  employee: string;
  /**
   * Whose field it is beyond doubt: two people can share a name, and a grant pressed on the wrong
   * one cannot be revoked, so the button names both, as the account screen's employee choice does.
   */
  identifier: string;
  qualifications: readonly Qualification[];
  /** The id of the refusal of this row's last grant, which marks the picker; none if not refused. */
  refusedBy: string | undefined;
  /** Called on every change of choice, so a success said before it is no longer read as current. */
  onEdit: () => void;
  /** Grants the chosen qualification, resolving to whether it was granted. */
  onSubmit: (qualification: string) => Promise<boolean>;
}) {
  const [choice, setChoice] = useState('');
  const [granting, setGranting] = useState(false);
  return (
    <form
      className="row"
      onSubmit={(event) => {
        event.preventDefault();
        if (granting) {
          return;
        }
        setGranting(true);
        void onSubmit(choice)
          .then((granted) => {
            if (granted) {
              setChoice('');
            }
          })
          .finally(() => setGranting(false));
      }}
    >
      <select
        value={choice}
        required
        aria-label={`Grant a qualification to ${employee}`}
        aria-invalid={refusedBy !== undefined}
        aria-describedby={refusedBy}
        onChange={(event) => {
          setChoice(event.target.value);
          onEdit();
        }}
      >
        <option value="">choose…</option>
        {qualifications.map((qualification) => (
          <option key={qualification.id} value={qualification.id}>
            {qualification.name}
          </option>
        ))}
      </select>
      <button
        type="submit"
        aria-disabled={granting}
        aria-label={`Grant to ${employee} · ${identifier}`}
      >
        Grant
      </button>
    </form>
  );
}

function ContractField({
  employee,
  refusedBy,
  onEdit,
  onSubmit,
}: {
  /** Whose field it is, so every row's input has a name of its own. */
  employee: string;
  /** The id of the refusal of this row's last contract, which marks the field; none if not refused. */
  refusedBy: string | undefined;
  /** Called on every edit, so a success said before it is no longer read as current. */
  onEdit: () => void;
  /** Sets the typed minutes; settles once the answer is in, whatever it was. */
  onSubmit: (minutes: number) => Promise<unknown>;
}) {
  const [minutes, setMinutes] = useState('');
  // A second contract sent meanwhile is a second change nobody made.
  const [setting, setSetting] = useState(false);
  return (
    <form
      className="row"
      onSubmit={(event) => {
        event.preventDefault();
        if (setting) {
          return;
        }
        setSetting(true);
        void onSubmit(Number(minutes)).finally(() => setSetting(false));
      }}
    >
      <input
        type="number"
        min="1"
        value={minutes}
        aria-label={`Contracted minutes per week for ${employee}`}
        aria-invalid={refusedBy !== undefined}
        aria-describedby={refusedBy}
        onChange={(event) => {
          setMinutes(event.target.value);
          onEdit();
        }}
        required
      />
      <button
        type="submit"
        aria-disabled={setting}
        aria-label={`Set contracted minutes for ${employee}`}
      >
        Set
      </button>
    </form>
  );
}
