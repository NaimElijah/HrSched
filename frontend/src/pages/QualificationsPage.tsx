import { useState } from 'react';
import { workforce } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';

/**
 * Admin: the qualifications this organisation recognises. Defining one takes an Admin.
 *
 * A qualification cannot be deleted, so a definition is sent once: the button waits for the answer.
 */
export function QualificationsPage() {
  const sessionKey = useSessionKey();
  const [name, setName] = useState('');
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);
  const [defining, setDefining] = useState(false);

  const qualifications = useAsync(() => workforce.findQualifications(), sessionKey);

  async function define(event: React.FormEvent) {
    event.preventDefault();
    if (defining) {
      return;
    }
    setFailure(null);
    setDone(null);
    setDefining(true);
    try {
      const defined = await workforce.defineQualification(name);
      setName('');
      // The field empties on success, so without a word nothing says the definition was stored.
      setDone(`Defined ${defined.name}.`);
      qualifications.reload();
    } catch (error) {
      setFailure(error);
    } finally {
      setDefining(false);
    }
  }

  return (
    <section>
      <h2>Qualifications</h2>
      <p className="hint">
        A qualification is a named ticket, scoped to this organisation. A shift may only require one
        this organisation has defined.
      </p>

      {/* Change events bubble, so any edit clears a success that would read as the next one's. */}
      <form onSubmit={define} onChange={() => setDone(null)} className="row">
        <label>
          Name
          <input value={name} onChange={(event) => setName(event.target.value)} required />
        </label>
        {/* aria-disabled rather than disabled, so focus stays on the button while it is out. */}
        <button type="submit" aria-disabled={defining}>
          Define
        </button>
      </form>

      <Failure error={failure} />
      <p className="done" role="status">
        {done}
      </p>
      <Failure error={qualifications.error} />
      <Busy busy={qualifications.busy} />

      {qualifications.value?.length === 0 ? (
        <p>No qualifications yet.</p>
      ) : (
        <ul>
          {(qualifications.value ?? []).map((qualification) => (
            <li key={qualification.id}>
              {qualification.name} <code>{qualification.id}</code>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
