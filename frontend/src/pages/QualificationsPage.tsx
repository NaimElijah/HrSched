import { useState } from 'react';
import { workforce } from '../api/workforce';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useIdentityKey } from '../identity/ActingAs';

/** Admin: the qualifications this organisation recognises. Defining one takes an Admin. */
export function QualificationsPage() {
  const identityKey = useIdentityKey();
  const [name, setName] = useState('');
  const [failure, setFailure] = useState<unknown>(null);

  const qualifications = useAsync(() => workforce.findQualifications(), identityKey);

  async function define(event: React.FormEvent) {
    event.preventDefault();
    setFailure(null);
    try {
      await workforce.defineQualification(name);
      setName('');
      qualifications.reload();
    } catch (error) {
      setFailure(error);
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
        <button type="submit">Define</button>
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
