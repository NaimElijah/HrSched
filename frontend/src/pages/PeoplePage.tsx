import { useState } from 'react';
import { workforce } from '../api/workforce';
import type { Qualification } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';

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
  // What the last change did. An added person may sort anywhere in the table, and a granted
  // qualification's picker empties, so neither is otherwise confirmed where the Admin is looking.
  const [done, setDone] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  const people = useAsync(() => workforce.findEmployees(), sessionKey);
  const qualifications = useAsync(() => workforce.findQualifications(), sessionKey);
  const qualificationNames = new Map(
    (qualifications.value ?? []).map((qualification) => [qualification.id, qualification.name]),
  );

  /**
   * @param said what to say once the action has succeeded, if anything
   * @returns whether the action succeeded
   */
  async function act(action: () => Promise<unknown>, said?: string): Promise<boolean> {
    setFailure(null);
    setDone(null);
    try {
      await action();
      setDone(said ?? null);
      people.reload();
      return true;
    } catch (error) {
      setFailure(error);
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

      <form
        className="row"
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
        <button type="submit" disabled={adding}>
          Add employee
        </button>
      </form>

      <Failure error={failure} />
      <p className="done" role="status">
        {done}
      </p>
      <Failure error={people.error} />
      <Busy busy={people.busy} />

      <table>
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
          {(people.value ?? []).map((employee) => (
            <tr key={employee.id}>
              <td>
                {employee.name}
                <br />
                <code>{employee.id}</code>
              </td>
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
                  onSubmit={(qualification) =>
                    act(
                      () => workforce.grantQualification(employee.id, qualification),
                      `Granted ${qualificationNames.get(qualification) ?? qualification} to ${employee.name}.`,
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
                  onSubmit={(minutes) => act(() => workforce.setContract(employee.id, minutes))}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
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
        void onSubmit(choice).then((granted) => {
          setGranting(false);
          if (granted) {
            setChoice('');
          }
        });
      }}
    >
      <select
        value={choice}
        required
        aria-label={`Grant a qualification to ${employee}`}
        onChange={(event) => setChoice(event.target.value)}
      >
        <option value="">choose…</option>
        {qualifications.map((qualification) => (
          <option key={qualification.id} value={qualification.id}>
            {qualification.name}
          </option>
        ))}
      </select>
      <button type="submit" disabled={granting} aria-label={`Grant to ${employee} · ${identifier}`}>
        Grant
      </button>
    </form>
  );
}

function ContractField({
  employee,
  onSubmit,
}: {
  /** Whose field it is, so every row's input has a name of its own. */
  employee: string;
  onSubmit: (minutes: number) => Promise<unknown>;
}) {
  const [minutes, setMinutes] = useState('');
  return (
    <form
      className="row"
      onSubmit={(event) => {
        event.preventDefault();
        void onSubmit(Number(minutes));
      }}
    >
      <input
        type="number"
        min="1"
        value={minutes}
        aria-label={`Contracted minutes per week for ${employee}`}
        onChange={(event) => setMinutes(event.target.value)}
        required
      />
      <button type="submit">Set</button>
    </form>
  );
}
