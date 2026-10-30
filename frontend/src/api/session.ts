/**
 * Everything this app knows about being signed in, except how to draw it.
 *
 * The access token lives in a module variable and **nowhere else** — not `localStorage`, not
 * `sessionStorage`, not a cookie this code writes, not IndexedDB. Anything a script can read is
 * something a cross-site scripting flaw can walk away with, and a token kept only in memory dies
 * with the tab. The session outlives that through the refresh cookie the browser holds and this
 * code cannot read (ADR-028).
 */

/** Who is signed in, mirroring the backend's session response. */
export interface Session {
  readonly userId: string;
  readonly kind: 'TENANT' | 'OPERATOR';
  readonly organisation: string | null;
  readonly role: 'EMPLOYEE' | 'SCHEDULER' | 'ADMIN' | null;
  readonly employee: string | null;
  readonly signInName: string;
  readonly passwordChangeRequired: boolean;
}

/** What the backend answers a sign-in, a refresh and a password change with. */
export interface SignedIn {
  readonly accessToken: string;
  readonly tokenType: string;
  readonly expiresIn: number;
  readonly session: Session;
}

let accessToken: string | null = null;
let session: Session | null = null;

/**
 * How a session ended, which decides what the shell says next.
 *
 * `ended` is a session that is over: the sign-in form may be offered. `sign-out-unconfirmed` is a
 * sign-out asked for but not yet confirmed by the backend, which clears the refresh cookie only
 * when it is: until then this browser may still be signed in, and the sign-in form would say it was
 * not (#250).
 */
export type SessionEnding = 'ended' | 'sign-out-unconfirmed';

const ENDED: SessionEnding = 'ended';
const SIGN_OUT_UNCONFIRMED: SessionEnding = 'sign-out-unconfirmed';

const listeners = new Set<(how: SessionEnding) => void>();
const heldListeners = new Set<(held: Session) => void>();

/**
 * Where tabs tell each other a session ended, and how. Only the two {@link SessionEnding} values
 * are ever posted: never a token, never who was signed in.
 *
 * The token lives in each tab's memory, so a sign-out in one tab would otherwise leave every other
 * tab still showing the names, absences and contracted hours it had loaded, for whoever sits down
 * at the computer next (#250).
 */
const SESSION_CHANNEL = 'hrsched.session';

// BroadcastChannel is absent in older browsers. Without it a sign-out stays in its own tab, which is
// the behaviour this app had before — degraded, not broken.
const otherTabs: BroadcastChannel | null =
  typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(SESSION_CHANNEL);

// A tab that hears how the session ended forgets it the same way, without announcing it again: two
// tabs answering each other would never stop.
otherTabs?.addEventListener('message', (event: MessageEvent<unknown>) => {
  if (event.data === ENDED || event.data === SIGN_OUT_UNCONFIRMED) {
    forget(event.data);
  }
});

/**
 * @returns the token to present, or null when nobody is signed in
 */
export function currentAccessToken(): string | null {
  return accessToken;
}

/**
 * @returns who is signed in, or null
 */
export function currentSession(): Session | null {
  return session;
}

/**
 * Remembers a session that has just begun or been renewed, and tells anybody watching.
 *
 * A renewal answers with the session as the backend holds it now, so a role or an employee link
 * changed since sign-in arrives here: the notification is what lets the shell stop showing, and
 * the pages stop acting on, the one it replaced.
 *
 * @param signedIn what the backend answered with
 */
export function holdSession(signedIn: SignedIn): void {
  accessToken = signedIn.accessToken;
  session = signedIn.session;
  for (const listener of [...heldListeners]) {
    listener(signedIn.session);
  }
}

/**
 * Forgets everything, and tells anybody watching — in this tab and in every other.
 *
 * Called when a refresh is refused, when a sign-out is confirmed, and whenever the backend stops
 * believing us. The notification is what lets the shell fall back to the sign-in screen from
 * anywhere, including from inside a request a page made. Every other tab of this app hears it too,
 * and ends its own session the same way.
 */
export function endSession(): void {
  forget(ENDED);
  otherTabs?.postMessage(ENDED);
}

/**
 * Forgets everything in this tab alone, and tells this tab's listeners it ended.
 *
 * For a session this tab may no longer act as, when the session the cookie now belongs to is
 * somebody else's and live: telling the other tabs would sign that account out of every tab it is
 * using.
 */
export function forgetSessionHere(): void {
  forget(ENDED);
}

/**
 * Forgets everything here and in every other tab, as a sign-out the backend has not yet confirmed.
 *
 * Called before the sign-out is sent, not after it is answered: somebody who clicks and walks away
 * has left the other tabs to whoever comes next, and an answer can take as long as a timeout, or
 * never come. Every tab says this browser may still be signed in until {@link endSession} says the
 * sign-out was confirmed (#250).
 */
export function beginSignOut(): void {
  forget(SIGN_OUT_UNCONFIRMED);
  otherTabs?.postMessage(SIGN_OUT_UNCONFIRMED);
}

function forget(how: SessionEnding): void {
  accessToken = null;
  session = null;
  for (const listener of [...listeners]) {
    listener(how);
  }
}

/**
 * @param listener called whenever a session ends, with how it ended
 * @returns a function that stops listening
 */
export function onSessionEnded(listener: (how: SessionEnding) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * @param listener called whenever a session is held — begun, renewed or begun again by a password
 *     change — with the session as the backend answered it
 * @returns a function that stops listening
 */
export function onSessionHeld(listener: (held: Session) => void): () => void {
  heldListeners.add(listener);
  return () => heldListeners.delete(listener);
}
