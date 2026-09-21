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
 * Forgets everything, and tells anybody watching.
 *
 * Called when a refresh fails, when signing out, and whenever the backend stops believing us. The
 * notification is what lets the shell fall back to the sign-in screen from anywhere, including from
 * inside a request a page made.
 */
export function endSession(): void {
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
