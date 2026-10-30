import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import type { ReactNode } from 'react';
import { auth } from '../api/client';
import { forgetSessionHere, onSessionEnded, onSessionHeld } from '../api/session';
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
 * `sign-out-unconfirmed` means a sign-out was asked for, in this tab or another, and the token
 * forgotten here, but the backend has not confirmed it: it is in flight, or it failed. The backend
 * clears the refresh cookie only when a sign-out succeeds, so until one does a reload restores the
 * session: on a shared computer, the previous person's. Offering the sign-in form would tell
 * somebody walking away that they had signed out (#250). A confirmed sign-out, from any tab, ends
 * it.
 */
export type SessionState =
  | 'restoring'
  | 'restore-failed'
  | 'signed-out'
  | 'sign-out-unconfirmed'
  | 'password-change-required'
  | 'signed-in';

/** Where a sign-out stands, which the `sign-out-unconfirmed` screen reports. */
export interface SignOutProgress {
  /** Whether a sign-out request from this tab is in flight. */
  readonly pending: boolean;
  /**
   * How many sign-out attempts from this tab have failed since a session last ended confirmed.
   * Counted so that a retry failing like the one before still changes what a live region says.
   */
  readonly failures: number;
  /** The last of those failures, kept for its trace reference; null when none failed. */
  readonly failure: unknown;
}

const NO_SIGN_OUT: SignOutProgress = { pending: false, failures: 0, failure: null };

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
   * Signs out, and is also the retry while the state is `sign-out-unconfirmed`. The state is that
   * from the moment it is called until the backend confirms. Never rejects: a sign-out the backend
   * did not confirm stays in that state instead.
   */
  readonly signOut: () => Promise<void>;
  readonly signOutProgress: SignOutProgress;
  /**
   * Whether a session that was on screen — signed in, or waiting on an unconfirmed sign-out — has
   * ended since anybody last signed in. False on a first load, where there is nothing to explain.
   */
  readonly sessionEnded: boolean;
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
  const [signOutProgress, setSignOutProgress] = useState<SignOutProgress>(NO_SIGN_OUT);
  const [sessionEnded, setSessionEnded] = useState(false);

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

  // Read by the listener below, which is registered once: a restore that finds the cookie refused
  // also ends a session, but nobody was looking at one, so there is nothing to explain. A layout
  // effect, so it is current from the commit that draws the screen: a passive one can still be
  // pending when a session ends.
  const somethingOnScreen = useRef(false);
  const sessionOnScreen = useRef<Session | null>(null);
  useLayoutEffect(() => {
    somethingOnScreen.current = session !== null || signOutUnconfirmed;
    sessionOnScreen.current = session;
  }, [session, signOutUnconfirmed]);

  useEffect(
    () =>
      onSessionEnded((how) => {
        setSession(null);
        setRestoring(false);
        setSignOutUnconfirmed(how === 'sign-out-unconfirmed');
        if (how === 'ended') {
          setSignOutProgress(NO_SIGN_OUT);
          if (somethingOnScreen.current) {
            setSessionEnded(true);
          }
        }
      }),
    [],
  );

  // A session held for the account on screen is that session renewed, and carries its role,
  // employee link and password flag as the backend holds them now (#254). One held while nothing is
  // on screen is left alone: a sign-in sets the session it was answered with, and a restore the one
  // it reads back. One held for another account while this one is on screen is refused before it
  // is held today; were it held, the client would act as that account under a shell naming this
  // one (#250), so this tab forgets its session instead.
  useEffect(
    () =>
      onSessionHeld((held) => {
        const shown = sessionOnScreen.current;
        if (shown === null) {
          return;
        }
        if (shown.userId === held.userId) {
          setSession(held);
        } else {
          forgetSessionHere();
        }
      }),
    [],
  );

  const signIn = useCallback(async (signInName: string, password: string) => {
    const signedIn = await auth.signIn(signInName, password);
    setSessionEnded(false);
    setSignOutProgress(NO_SIGN_OUT);
    setSession(signedIn.session);
  }, []);

  const signOut = useCallback(async () => {
    setSignOutProgress((progress) => ({ ...progress, pending: true }));
    try {
      await auth.signOut();
    } catch (failure) {
      // Every caller fires this and forgets it, so a rejection here would be one nobody hears. It
      // is handled by being shown: the session already ended as `sign-out-unconfirmed` before the
      // request was sent, and that screen reports this failure and its reference (#250).
      setSignOutProgress((progress) => ({
        pending: false,
        failures: progress.failures + 1,
        failure,
      }));
    }
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
      signOutProgress,
      sessionEnded,
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
      signOutProgress,
      sessionEnded,
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
