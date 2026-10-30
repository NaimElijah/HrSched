import { useState } from 'react';
import { workforce } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';

/**
 * Admin: who works here, what they hold, and what they are contracted to.
 *
 * Nobody added can be removed, so an addition is sent once: the button waits for the answer.
 */
export function PeoplePage() {
  const sessionKey = useSessionKey();
  const [name, setName] = useState('');
  const [failure, setFailure] = useState<unknown>(null);
  const [adding, setAdding] = useState(false);

  const people = useAsync(() => workforce.findEmployees(), sessionKey);
  const qualifications = useAsync(() => workforce.findQualifications(), sessionKey);
  const qualificationNames = new Map(
    (qualifications.value ?? []).map((qualification) => [qualification.id, qualification.name]),
  );

  async function act(action: () => Promise<unknown>) {
    setFailure(null);
    try {
      await action();
      people.reload();
    } catch (error) {
      setFailure(error);
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
          }).finally(() => setAdding(false));
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
                <select
                  defaultValue=""
                  aria-label={`Grant a qualification to ${employee.name}`}
                  onChange={(event) => {
                    const qualification = event.target.value;
                    if (qualification !== '') {
                      void act(() => workforce.grantQualification(employee.id, qualification));
                    }
                  }}
                >
                  <option value="">choose…</option>
                  {(qualifications.value ?? []).map((qualification) => (
                    <option key={qualification.id} value={qualification.id}>
                      {qualification.name}
                    </option>
                  ))}
                </select>
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
