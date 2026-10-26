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

const listeners = new Set<() => void>();

/**
 * Where tabs tell each other a session ended, and what they say.
 *
 * The token lives in each tab's memory, so a sign-out in one tab would otherwise leave every other
 * tab still showing the names, absences and contracted hours it had loaded, for whoever sits down
 * at the computer next (#250).
 */
const SESSION_CHANNEL = 'hrsched.session';
const ENDED = 'ended';

// BroadcastChannel is absent in older browsers. Without it a sign-out stays in its own tab, which is
// the behaviour this app had before — degraded, not broken.
const otherTabs: BroadcastChannel | null =
  typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(SESSION_CHANNEL);

// A tab that hears the session ended forgets it without announcing it again: two tabs answering
// each other would never stop.
otherTabs?.addEventListener('message', (event) => {
  if (event.data === ENDED) {
    forget();
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
 * Remembers a session that has just begun or been renewed.
 *
 * @param signedIn what the backend answered with
 */
export function holdSession(signedIn: SignedIn): void {
  accessToken = signedIn.accessToken;
  session = signedIn.session;
}

/**
 * Forgets everything, and tells anybody watching — in this tab and in every other.
 *
 * Called when a refresh is refused, when signing out, and whenever the backend stops believing us.
 * The notification is what lets the shell fall back to the sign-in screen from anywhere, including
 * from inside a request a page made. Every other tab of this app hears it too, and ends its own
 * session the same way.
 */
export function endSession(): void {
  forget();
  otherTabs?.postMessage(ENDED);
}

function forget(): void {
  accessToken = null;
  session = null;
  for (const listener of [...listeners]) {
    listener();
  }
}

/**
 * @param listener called whenever a session ends
 * @returns a function that stops listening
 */
export function onSessionEnded(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
