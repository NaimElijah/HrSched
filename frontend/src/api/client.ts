import { ApiError, toApiError } from './problem';
import { currentAccessToken, endSession, holdSession } from './session';
import type { SignedIn } from './session';

/**
 * The header the backend puts the request's trace id on.
 *
 * Named here rather than inlined because it is the one header this module reads instead of writes,
 * and because it is the half of a contract whose other half is `TraceIdResponseFilter`.
 */
const TRACE_ID_HEADER = 'X-Trace-Id';

/**
 * Required by the endpoints whose credential is the refresh cookie.
 *
 * Its presence is the whole check; the value is never read. A browser will not attach a header like
 * this to a cross-site request without a CORS preflight, and the backend configures no CORS, so a
 * page on another origin cannot cause a renewal or a sign-out (ADR-028).
 */
const REQUESTED_WITH = 'XMLHttpRequest';

/** Everything under here is authentication itself, and a 401 from it is never worth retrying. */
const AUTH_PREFIX = '/api/auth/';

/**
 * Serialises refreshes across tabs.
 *
 * Two tabs renewing with one cookie is exactly the pattern reuse detection is built to punish: the
 * second would present a token the first had already exchanged, and the backend would — correctly —
 * conclude the token had been stolen and end the session. A Web Lock makes the tabs take turns.
 */
const REFRESH_LOCK = 'hrsched.refresh';

/** One in-flight refresh at a time within this tab, shared by every request waiting on it. */
let refreshing: Promise<boolean> | null = null;

/**
 * The only module in the app that calls fetch, and the only one that knows a header exists.
 *
 * Keeping that in one place is what made the identity headers deletable in a single edit, and what
 * stops a page quietly inventing its own idea of who is acting. The bearer token is now the whole
 * of what says who that is.
 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const first = await send(path, init);
  if (first.status !== 401 || path.startsWith(AUTH_PREFIX)) {
    return interpret<T>(path, first);
  }
  // One renewal, then one retry, and never more. A second 401 means the session is genuinely over,
  // and retrying past that would be a loop that looks like an outage.
  if (!(await refreshOnce())) {
    return interpret<T>(path, first);
  }
  return interpret<T>(path, await send(path, init));
}

interface Answered {
  readonly status: number;
  readonly ok: boolean;
  readonly text: string;
  readonly reference: string | null;
}

async function send(path: string, init: RequestInit): Promise<Answered> {
  const headers = new Headers(init.headers);
  const token = currentAccessToken();
  if (token !== null) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  if (init.body !== undefined) {
    headers.set('Content-Type', 'application/json');
  }
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' });
  return {
    status: response.status,
    ok: response.ok,
    text: await response.text(),
    reference: response.headers.get(TRACE_ID_HEADER),
  };
}

function interpret<T>(path: string, answered: Answered): T {
  if (!answered.ok) {
    // No body at all is left for toApiError to describe by its status. An empty detail would be a
    // failure that says nothing, which is what a gateway with no backend behind it sends.
    throw toApiError(
      answered.status,
      parseOrNull(answered.text) ?? (answered.text.length === 0 ? null : { detail: answered.text }),
      answered.reference,
    );
  }
  if (answered.status === 204 || answered.text.length === 0) {
    return undefined as T;
  }
  const body = parseOrNull(answered.text);
  if (body === null) {
    // A 200 carrying something other than JSON is a broken deployment, not a value. Casting it
    // would hand a page an object where it expects an array, and the render would throw.
    throw new ApiError(
      answered.status,
      `Expected JSON from ${path} but the body was not JSON.`,
      [],
      answered.reference,
    );
  }
  return body as T;
}

/**
 * Renews the session at most once, however many requests are waiting.
 *
 * Single-flight within the tab, and serialised across tabs by a Web Lock where the browser has one.
 * A failed renewal ends the session rather than being retried: the cookie is either good or gone.
 *
 * @returns whether there is a usable token afterwards
 */
async function refreshOnce(): Promise<boolean> {
  refreshing ??= (async () => {
    try {
      return await underRefreshLock(async () => {
        const answered = await send('/api/auth/refresh', {
          method: 'POST',
          headers: { 'X-Requested-With': REQUESTED_WITH },
        });
        if (!answered.ok) {
          endSession();
          return false;
        }
        holdSession(interpret<SignedIn>('/api/auth/refresh', answered));
        return true;
      });
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

async function underRefreshLock<T>(run: () => Promise<T>): Promise<T> {
  // navigator.locks is absent in older browsers and in jsdom. Without it the tabs simply do not
  // take turns, which is the behaviour this app had before — degraded, not broken.
  if (typeof navigator === 'undefined' || navigator.locks === undefined) {
    return run();
  }
  return navigator.locks.request(REFRESH_LOCK, run);
}

function parseOrNull(text: string): unknown {
  if (text.length === 0) {
    return null;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

/**
 * Builds an API path, encoding every identifier interpolated into it.
 *
 * An identifier is one path segment and has to stay one: `fetch` resolves `..` and splits on `/`,
 * so an unencoded value can quietly address a different endpoint. Every path carrying an identifier
 * is built with this, whether the identifier came from the server or was typed by hand.
 *
 * Encoding keeps a `/` inside its segment, but nothing keeps `.` or `..` there: encodeURIComponent
 * leaves dots alone, and the URL parser reads `%2e%2e` as `..` anyway. Those two are refused.
 *
 * @param segments the literal parts of the path
 * @param identifiers the values placed between them
 * @returns the path, with each identifier percent-encoded
 * @throws Error when an identifier is exactly `.` or `..`, which no encoding can keep in its segment
 */
export function path(segments: TemplateStringsArray, ...identifiers: readonly string[]): string {
  if (identifiers.some((identifier) => identifier === '.' || identifier === '..')) {
    throw new Error(
      'An identifier in a path cannot be "." or "..": it would address another endpoint.',
    );
  }
  return String.raw(
    { raw: segments },
    ...identifiers.map((identifier) => encodeURIComponent(identifier)),
  );
}

export const api = {
  get: <T>(path: string): Promise<T> => request<T>(path),

  post: <T>(path: string, body?: unknown): Promise<T> =>
    request<T>(path, {
      method: 'POST',
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    }),

  put: <T>(path: string, body?: unknown): Promise<T> =>
    request<T>(path, {
      method: 'PUT',
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    }),

  delete: <T>(path: string): Promise<T> => request<T>(path, { method: 'DELETE' }),
};

/**
 * The authentication endpoints, which are the only ones whose credential is not the access token.
 *
 * They live here rather than in a module of their own because they are the one place `fetch` is
 * called with something other than a bearer token, and this module is the only one allowed to call
 * `fetch` at all.
 */
export const auth = {
  /**
   * @param signInName the name submitted
   * @param password the password submitted
   * @returns who is now signed in
   */
  signIn: async (signInName: string, password: string): Promise<SignedIn> => {
    const signedIn = await request<SignedIn>('/api/auth/sign-in', {
      method: 'POST',
      body: JSON.stringify({ signInName, password }),
    });
    holdSession(signedIn);
    return signedIn;
  },

  /**
   * Restores a session from the refresh cookie, which is how a reload keeps somebody signed in.
   *
   * @returns whether a session was restored
   */
  restore: (): Promise<boolean> => refreshOnce(),

  /**
   * Ends the session here and at the backend. Always forgets locally, even if the request fails:
   * somebody who asked to sign out must not be left signed in because the network was down.
   */
  signOut: async (): Promise<void> => {
    try {
      await request<void>('/api/auth/sign-out', {
        method: 'POST',
        headers: { 'X-Requested-With': REQUESTED_WITH },
      });
    } finally {
      endSession();
    }
  },

  /**
   * @returns the caller's own session, read fresh from the backend
   */
  readSession: (): Promise<SignedIn['session']> => api.get('/api/auth/session'),

  /**
   * @param currentPassword the password in use
   * @param newPassword the password wanted instead
   * @returns the new session the change begins
   */
  changePassword: async (currentPassword: string, newPassword: string): Promise<SignedIn> => {
    const signedIn = await request<SignedIn>('/api/auth/password', {
      method: 'POST',
      body: JSON.stringify({ currentPassword, newPassword }),
    });
    holdSession(signedIn);
    return signedIn;
  },
};
