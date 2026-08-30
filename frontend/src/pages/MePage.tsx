import { useState } from 'react';
import { workforce } from '../api/workforce';
import type { PreferenceStrength } from '../api/workforce';
import { Failure } from '../components/Feedback';
import { useActingAs } from '../identity/ActingAs';

/**
 * Employee self-service: when I cannot work, and how I feel about a span.
 *
 * Unavailability is hard — nobody is rostered during it. A preference is soft and never blocks a
 * roster; today nothing consumes preferences at all, because the objectives a generator would
 * optimise are not modelled (ADR-010). This page says so rather than implying the preference will
 * be honoured.
 *
 * Acting on your own record requires the acting identity to carry your employee link (ADR-012),
 * which is what the "acting as employee" control above sets.
 */
export function MePage() {
  const { identity } = useActingAs();
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [strength, setStrength] = useState<PreferenceStrength>('PREFERS');
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const employee = identity.employee;

  async function act(action: () => Promise<unknown>, message: string) {
    setFailure(null);
    setDone(null);
    try {
      await action();
      setDone(message);
    } catch (error) {
      setFailure(error);
    }
  }

  if (employee === null) {
    return (
      <section>
        <h2>My availability</h2>
        <p className="hint">
          Choose who you are in <strong>acting as employee</strong> above. A user and an employee
          are different identities on purpose, so the link has to be stated — without it the backend
          cannot tell that you are the subject of your own record.
        </p>
      </section>
    );
  }

  return (
    <section>
      <h2>My availability</h2>
      <p className="hint">
        Unavailability is hard: nobody is rostered during it. A preference is soft and never blocks
        a roster — and nothing optimises for it yet, because objectives are not modelled.
      </p>

      <form className="grid">
        <label>
          From
          <input
            type="datetime-local"
            value={start}
            onChange={(event) => setStart(event.target.value)}
            required
          />
        </label>
        <label>
          To
          <input
            type="datetime-local"
            value={end}
            onChange={(event) => setEnd(event.target.value)}
            required
          />
        </label>
        <label>
          Preference
          <select
            value={strength}
            onChange={(event) => setStrength(event.target.value as PreferenceStrength)}
          >
            <option value="PREFERS">Would like to work this</option>
            <option value="PREFERS_NOT">Would rather not</option>
          </select>
        </label>
        <button
          type="button"
          onClick={() =>
            void act(
              () =>
                workforce.declareUnavailability(
                  identity,
                  employee,
                  new Date(start).toISOString(),
                  new Date(end).toISOString(),
                ),
              'Recorded: you cannot work then.',
            )
          }
        >
          I cannot work this
        </button>
        <button
          type="button"
          onClick={() =>
            void act(
              () =>
                workforce.statePreference(
                  identity,
                  employee,
                  new Date(start).toISOString(),
                  new Date(end).toISOString(),
                  strength,
                ),
              'Recorded: a preference, which never blocks a roster.',
            )
          }
        >
          State a preference
        </button>
      </form>

      {done === null ? null : <p className="done">{done}</p>}
      <Failure error={failure} />
    </section>
  );
}
