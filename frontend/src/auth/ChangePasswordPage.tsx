import { useState } from 'react';
import { ApiError } from '../api/problem';
import { useSession } from './SessionProvider';

/**
 * Changing one's own password, and the only screen reachable while a temporary one is pending.
 *
 * **No length or shape is suggested here, and no field carries a default.** What a password must
 * satisfy is the backend's rule, and it says so in its own words when it refuses; repeating it in
 * the UI would be a second copy that drifts, and pre-filling anything would be this application
 * choosing a password for somebody.
 *
 * The confirmation field is checked here rather than sent, because two boxes disagreeing is a typo
 * rather than a decision the backend has any view about.
 */
export function ChangePasswordPage() {
  const { state, session, changePassword, signOut } = useSession();
  const [current, setCurrent] = useState('');
  const [replacement, setReplacement] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [failure, setFailure] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // From the Password tab an accepted change alters nothing else on screen, so it is said here.
  const [done, setDone] = useState<string | null>(null);

  const required = state === 'password-change-required';

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setFailure(null);
    setDone(null);
    if (replacement !== confirmation) {
      setFailure('The two new passwords do not match.');
      return;
    }
    setSubmitting(true);
    try {
      await changePassword(current, replacement);
      setCurrent('');
      setReplacement('');
      setConfirmation('');
      setDone('Your password has been changed.');
    } catch (error) {
      setFailure(error instanceof ApiError ? error.message : String(error));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="change-password">
      <h2>Change your password</h2>
      {required ? (
        <p className="hint">
          Your password was set by somebody else. Change it before doing anything else.
        </p>
      ) : null}
      <form onSubmit={submit}>
        <label>
          Current password
          <input
            type="password"
            value={current}
            onChange={(event) => setCurrent(event.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        <label>
          New password
          <input
            type="password"
            value={replacement}
            onChange={(event) => setReplacement(event.target.value)}
            autoComplete="new-password"
            required
          />
        </label>
        <label>
          New password again
          <input
            type="password"
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
            autoComplete="new-password"
            required
          />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Changing…' : 'Change password'}
        </button>
      </form>
      <p className="done" role="status">
        {done}
      </p>
      {failure === null ? null : (
        <div className="failure" role="alert">
          {failure}
        </div>
      )}
      {required ? (
        <p className="hint">
          Signed in as {session?.signInName}.{' '}
          <button type="button" className="link" onClick={() => void signOut()}>
            Sign out
          </button>
        </p>
      ) : null}
    </section>
  );
}
