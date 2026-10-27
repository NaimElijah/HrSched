import { useEffect, useRef } from 'react';
import { Failure } from '../components/Feedback';
import { useSession } from './SessionProvider';
import type { SignOutProgress } from './SessionProvider';

/**
 * What the screen's live region says. It stays in the page while silent, so that each change is
 * announced, and a retry that fails like the one before still says something new.
 */
function progressOf({ pending, failures }: SignOutProgress): string {
  if (pending) {
    return failures === 0
      ? 'Waiting for the server to confirm the sign-out…'
      : 'Trying to sign out again…';
  }
  return failures > 1 ? `The sign-out failed again, after ${String(failures)} attempts.` : '';
}

/**
 * The screen for a sign-out the backend has not confirmed, asked for in this tab or another.
 *
 * The backend clears the refresh cookie only when a sign-out succeeds, so until then a reload
 * restores the session: on a shared computer, for whoever comes next (#250). This says so instead
 * of offering the sign-in form, and offers the sign-out again. It takes focus on its heading
 * because whoever was using the shell a moment ago had focus on a button that no longer exists.
 */
export function SignOutUnconfirmedPage() {
  const { signOut, signOutProgress } = useSession();
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    heading.current?.focus();
  }, []);

  return (
    <section>
      <h2 ref={heading} tabIndex={-1}>
        Sign-out not confirmed
      </h2>
      <p className="failure" role="alert">
        The server has not confirmed the sign-out, so this browser may still be signed in. Try again
        before leaving this computer.
      </p>
      <Failure error={signOutProgress.failure} />
      {/* aria-disabled rather than disabled, so focus stays on the button while a retry is out. */}
      <button
        type="button"
        aria-disabled={signOutProgress.pending}
        onClick={() => {
          if (!signOutProgress.pending) {
            void signOut();
          }
        }}
      >
        Try signing out again
      </button>
      <p className="busy" role="status">
        {progressOf(signOutProgress)}
      </p>
    </section>
  );
}
