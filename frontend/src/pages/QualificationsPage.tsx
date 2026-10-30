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
  const [defining, setDefining] = useState(false);

  const qualifications = useAsync(() => workforce.findQualifications(), sessionKey);

  async function define(event: React.FormEvent) {
    event.preventDefault();
    if (defining) {
      return;
    }
    setFailure(null);
    setDefining(true);
    try {
      await workforce.defineQualification(name);
      setName('');
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

      <form onSubmit={define} className="row">
        <label>
          Name
          <input value={name} onChange={(event) => setName(event.target.value)} required />
        </label>
        <button type="submit" disabled={defining}>
          Define
        </button>
      </form>

      <Failure error={failure} />
      <Failure error={qualifications.error} />
      <Busy busy={qualifications.busy} />

      <ul>
        {(qualifications.value ?? []).map((qualification) => (
          <li key={qualification.id}>
            {qualification.name} <code>{qualification.id}</code>
          </li>
        ))}
      </ul>
    </section>
  );
}
