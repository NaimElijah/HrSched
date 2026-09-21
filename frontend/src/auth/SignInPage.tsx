import { useState } from 'react';
import { ApiError } from '../api/problem';
import { useSession } from './SessionProvider';

/**
 * One refusal for every way signing in can fail.
 *
 * The backend answers an unknown name, a wrong password, a disabled account and a locked one
 * identically, and this must not undo that by guessing at a friendlier message. "That name is not
 * registered" would tell somebody trying names which ones exist here; "your account is locked"
 * would tell them their guessing had been noticed.
 */
const REFUSED = 'Those credentials were not accepted.';

/** The way into the application: there is nothing else to do until this succeeds. */
export function SignInPage() {
  const { signIn } = useSession();
  const [signInName, setSignInName] = useState('');
  const [password, setPassword] = useState('');
  const [failure, setFailure] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setFailure(null);
    setSubmitting(true);
    try {
      await signIn(signInName, password);
    } catch (error) {
      setFailure(error instanceof ApiError && error.isUnauthenticated ? REFUSED : asMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="sign-in">
      <h2>Sign in</h2>
      <form onSubmit={submit}>
        <label>
          Sign-in name
          <input
            value={signInName}
            onChange={(event) => setSignInName(event.target.value)}
            autoComplete="username"
            required
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      {failure === null ? null : (
        <div className="failure" role="alert">
          {failure}
        </div>
      )}
      <p className="hint">
        Accounts are created for you. An administrator sets a temporary password, which you change
        the first time you sign in.
      </p>
    </section>
  );
}

/** Anything that is not a refusal is a real failure and is shown as itself. */
function asMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return error instanceof Error ? error.message : String(error);
}
