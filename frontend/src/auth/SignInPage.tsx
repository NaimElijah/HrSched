import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/problem';
import { useSession } from './SessionProvider';

/**
 * Why the sign-in page replaced a screen. One sentence covers every way a session on screen ends —
 * a sign-out here or in another tab, or the backend refusing to renew it — and it is true of each.
 */
const SIGNED_OUT = 'You have been signed out. Sign in to continue.';

/**
 * One refusal for every way signing in can fail.
 *
 * The backend answers an unknown name, a wrong password, a disabled account and a locked one
 * identically, and this must not undo that by guessing at a friendlier message. "That name is not
 * registered" would tell somebody trying names which ones exist here; "your account is locked"
 * would tell them their guessing had been noticed.
 */
const REFUSED = 'Those credentials were not accepted.';

/**
 * The way into the application: there is nothing else to do until this succeeds.
 *
 * When it replaces a session that was on screen, it says why and takes focus on its heading:
 * somebody who was using the shell a moment ago would otherwise see the app apparently fail, and a
 * screen-reader user would hear nothing. On a first load it does neither.
 */
export function SignInPage() {
  const { signIn, sessionEnded } = useSession();
  const [signInName, setSignInName] = useState('');
  const [password, setPassword] = useState('');
  const [failure, setFailure] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [reason, setReason] = useState<string | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (!sessionEnded) {
      return;
    }
    heading.current?.focus();
    // A live region announces a change to what it holds, not what it was inserted holding, so the
    // reason arrives in a second render, once the empty region is already in the page.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- that second render is the point
    setReason(SIGNED_OUT);
  }, [sessionEnded]);

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
      <h2 ref={heading} tabIndex={-1}>
        Sign in
      </h2>
      <p className="done" role="status">
        {reason}
      </p>
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
