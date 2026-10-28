import { render } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import { holdSession } from '../api/session';
import type { Session } from '../api/session';
import { SessionContext } from '../auth/SessionProvider';
import type { SessionContextValue } from '../auth/SessionProvider';

/** A signed-in tenant Admin, which is what most screens are written for. */
export const A_TENANT: Session = {
  userId: '33333333-3333-4333-8333-333333333333',
  kind: 'TENANT',
  organisation: '22222222-2222-4222-8222-222222222222',
  role: 'ADMIN',
  employee: null,
  signInName: 'ada@example.com',
  passwordChangeRequired: false,
};

/** The platform operator: no organisation, no role, no employee. */
export const THE_OPERATOR: Session = {
  userId: '44444444-4444-4444-8444-444444444444',
  kind: 'OPERATOR',
  organisation: null,
  role: null,
  employee: null,
  signInName: 'operator@example.com',
  passwordChangeRequired: false,
};

/**
 * Renders a screen as somebody already signed in.
 *
 * Supplies the session context directly rather than driving the restore handshake: a test about the
 * shifts table should not also be a test about refreshing a cookie. It also hands the client a
 * token, so the request a screen makes carries the same headers it would in a browser.
 *
 * @param ui the screen to render
 * @param session who is signed in; a tenant Admin by default
 */
export function renderSignedIn(ui: ReactElement, session: Session = A_TENANT) {
  return render(<SignedIn session={session}>{ui}</SignedIn>);
}

/**
 * The provider {@link renderSignedIn} wraps with, for a test that needs to render it itself.
 *
 * @param session who is signed in
 * @param children the screen
 */
export function SignedIn({ session, children }: { session: Session; children: ReactNode }) {
  holdSession({
    accessToken: 'a-test-access-token',
    tokenType: 'Bearer',
    expiresIn: 600,
    session,
  });
  const value: SessionContextValue = {
    state: session.passwordChangeRequired ? 'password-change-required' : 'signed-in',
    session,
    restoreFailure: null,
    retryRestore: () => undefined,
    key: `${session.userId}|${String(session.passwordChangeRequired)}`,
    signIn: () => Promise.resolve(),
    signOut: () => Promise.resolve(),
    signOutProgress: { pending: false, failures: 0, failure: null },
    sessionEnded: false,
    changePassword: () => Promise.resolve(),
  };
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
