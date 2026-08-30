import { useState } from 'react';
import { workforce } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { identityKey } from '../api/client';
import { useActingAs } from '../identity/ActingAs';

/** Admin: who works here, what they hold, and what they are contracted to. */
export function PeoplePage() {
  const { identity } = useActingAs();
  const [name, setName] = useState('');
  const [failure, setFailure] = useState<unknown>(null);

  const people = useAsync(() => workforce.findEmployees(identity), identityKey(identity));
  const qualifications = useAsync(
    () => workforce.findQualifications(identity),
    identityKey(identity),
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
        a Scheduler. Switch to EMPLOYEE above and this list becomes a 403.
      </p>

      <form
        className="row"
        onSubmit={(event) => {
          event.preventDefault();
          void act(async () => {
            await workforce.addEmployee(identity, name);
            setName('');
          });
        }}
      >
        <label>
          Name
          <input value={name} onChange={(event) => setName(event.target.value)} required />
        </label>
        <button type="submit">Add employee</button>
      </form>

      <Failure error={failure} />
      <Failure error={people.error} />
      <Busy busy={people.busy} />

      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Qualifications</th>
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
              <td>{employee.qualifications.length}</td>
              <td>
                <select
                  defaultValue=""
                  aria-label={`Grant a qualification to ${employee.name}`}
                  onChange={(event) => {
                    const qualification = event.target.value;
                    if (qualification !== '') {
                      void act(() =>
                        workforce.grantQualification(identity, employee.id, qualification),
                      );
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
                <ContractField
                  onSubmit={(minutes) =>
                    act(() => workforce.setContract(identity, employee.id, minutes))
                  }
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function ContractField({ onSubmit }: { onSubmit: (minutes: number) => Promise<unknown> }) {
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
        aria-label="Contracted minutes per week"
        onChange={(event) => setMinutes(event.target.value)}
        required
      />
      <button type="submit">Set</button>
    </form>
  );
}
