import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { auth } from '../api/client';
import { onSessionEnded } from '../api/session';
import type { Session } from '../api/session';

/**
 * What state the app is in, from the session's point of view.
 *
 * `restoring` is its own state rather than a loading flag because the first thing this app does on
 * every load is ask the backend whether the refresh cookie is still good. Showing the sign-in page
 * during that moment would flash it at somebody who is, in fact, signed in.
 *
 * `password-change-required` is its own state for a stronger reason: an account in it may do
 * exactly two things, and the shell must not be reachable at all until it is out of it.
 *
 * `restore-failed` means the restore could not finish: either the backend was not reached or did
 * not answer, so nobody knows whether the cookie is good, or the cookie was good but the session
 * could not be read back. Neither is `signed-out`, and offering the sign-in form would tell
 * somebody something untrue or something nobody knows (#139).
 *
 * `sign-out-unconfirmed` means a sign-out was asked for and the token forgotten here, but the
 * backend never confirmed it. The backend clears the refresh cookie only when a sign-out succeeds,
 * so until one does a reload restores the session: on a shared computer, the previous person's.
 * Offering the sign-in form would tell somebody walking away that they had signed out (#250).
 */
export type SessionState =
  | 'restoring'
  | 'restore-failed'
  | 'signed-out'
  | 'sign-out-unconfirmed'
  | 'password-change-required'
  | 'signed-in';

interface SessionContextValue {
  readonly state: SessionState;
  readonly session: Session | null;
  /** Why the restore failed while the state is `restore-failed`, and null otherwise. */
  readonly restoreFailure: unknown;
  /** Runs the whole restore again, from the refresh onwards. */
  readonly retryRestore: () => void;
  /** Changes whenever who is signed in changes, so loaded answers are asked again. */
  readonly key: string;
  readonly signIn: (signInName: string, password: string) => Promise<void>;
  /**
   * Signs out, and is also the retry while the state is `sign-out-unconfirmed`. Never rejects: a
   * sign-out the backend did not confirm becomes that state instead.
   */
  readonly signOut: () => Promise<void>;
  readonly changePassword: (current: string, replacement: string) => Promise<void>;
}

/**
 * Exported so a test can supply a session directly instead of driving the whole restore handshake
 * for a screen that is not about signing in. Nothing in the application reads it; everything uses
 * {@link useSession}.
 */
export const SessionContext = createContext<SessionContextValue | null>(null);

export type { SessionContextValue };

function stateOf(session: Session | null): SessionState {
  if (session === null) {
    return 'signed-out';
  }
  return session.passwordChangeRequired ? 'password-change-required' : 'signed-in';
}

/**
 * Owns the session for the whole app.
 *
 * It holds no token: the client does, in memory, and this only holds who that token belongs to so
 * that the shell can be drawn. Handing the token to React state would put it somewhere a component
 * could pass it on by accident.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [restoring, setRestoring] = useState(true);
  const [restoreFailure, setRestoreFailure] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);
  const [signOutUnconfirmed, setSignOutUnconfirmed] = useState(false);

  useEffect(() => {
    let abandoned = false;
    void (async () => {
      try {
        const restored = await auth.restore();
        if (abandoned) {
          return;
        }
        // The refresh answered with a session, but read it back rather than trusting the copy: a
        // password changed in another tab is exactly the kind of thing that makes them differ. A
        // read that fails is a failed restore, not a sign-out: the token just issued is still live.
        setSession(restored ? await auth.readSession() : null);
      } catch (failure) {
        if (abandoned) {
          return;
        }
        setRestoreFailure(failure);
      }
      setRestoring(false);
    })();
    return () => {
      abandoned = true;
    };
  }, [attempt]);

  const retryRestore = useCallback(() => {
    setRestoreFailure(null);
    setRestoring(true);
    setAttempt((previous) => previous + 1);
  }, []);

  useEffect(
    () =>
      onSessionEnded(() => {
        setSession(null);
        setRestoring(false);
      }),
    [],
  );

  const signIn = useCallback(async (signInName: string, password: string) => {
    setSession((await auth.signIn(signInName, password)).session);
  }, []);

  const signOut = useCallback(async () => {
    // Every caller fires this and forgets it, so a failure here would be a rejection nobody hears.
    // It is handled by becoming a state the shell shows instead: the token is already forgotten,
    // and what is left to say is that the refresh cookie may not be (#250).
    try {
      await auth.signOut();
      setSignOutUnconfirmed(false);
    } catch {
      setSignOutUnconfirmed(true);
    }
    setSession(null);
  }, []);

  const changePassword = useCallback(async (current: string, replacement: string) => {
    setSession((await auth.changePassword(current, replacement)).session);
  }, []);

  const value = useMemo<SessionContextValue>(
    () => ({
      state: restoring
        ? 'restoring'
        : restoreFailure !== null
          ? 'restore-failed'
          : signOutUnconfirmed
            ? 'sign-out-unconfirmed'
            : stateOf(session),
      session,
      restoreFailure,
      retryRestore,
      // The password flag is in the key on purpose: clearing it is a different app, and everything
      // loaded while it was set was loaded by somebody who could not reach any of it.
      key: session === null ? '' : `${session.userId}|${String(session.passwordChangeRequired)}`,
      signIn,
      signOut,
      changePassword,
    }),
    [
      restoring,
      restoreFailure,
      retryRestore,
      signOutUnconfirmed,
      session,
      signIn,
      signOut,
      changePassword,
    ],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (value === null) {
    throw new Error('useSession must be used inside a SessionProvider');
  }
  return value;
}

/**
 * The reload key pages hand {@code useAsync}.
 *
 * Every answer on screen belongs to whoever asked for it, so a change of session invalidates all of
 * them.
 */
export function useSessionKey(): string {
  return useSession().key;
}
