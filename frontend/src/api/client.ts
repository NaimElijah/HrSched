import { ApiError, toApiError } from './problem';
import {
  beginSignOut,
  currentAccessToken,
  currentSession,
  endSession,
  forgetSessionHere,
  holdSession,
} from './session';
import type { Session, SignedIn } from './session';

/**
 * The header the backend puts the request's trace id on.
 *
 * Named here rather than inlined because it is the one header this module reads instead of writes,
 * and because it is the half of a contract whose other half is `TraceIdResponseFilter`.
 */
const TRACE_ID_HEADER = 'X-Trace-Id';

/**
 * The one media type whose failure body is a sentence to show as it is. Anything else that is not
 * JSON is a page or a payload written for something other than an alert, such as the HTML a gateway
 * answers with when there is no backend behind it.
 */
const PLAIN_TEXT = 'text/plain';

/**
 * Sent with every cookie-credentialed request, which the backend requires of them.
 *
 * Its presence is the whole check; the value is never read. A browser will not attach a header like
 * this to a cross-site request without a CORS preflight, and the backend configures no CORS, so a
 * page on another origin cannot cause a renewal or a sign-out (ADR-028).
 */
const REQUESTED_WITH = 'XMLHttpRequest';

/**
 * The endpoints whose credential is the refresh cookie. They are sent `X-Requested-With`, and no
 * access token.
 *
 * The backend refuses a token that fails verification on every route, before the controller reads
 * the cookie, and the token held when these are sent is usually one that has just expired: renewal
 * happens because it did. Sending it would refuse the renewal and leave a sign-out undone (#179).
 * Not all of `/api/auth/`: reading the session and changing the password need the token.
 */
const COOKIE_CREDENTIALED: ReadonlySet<string> = new Set([
  '/api/auth/refresh',
  '/api/auth/sign-out',
]);

/**
 * The requests whose 401 is an answer about their own credential, which renewing the access token
 * cannot change: a name and password refused, or a cookie refused.
 *
 * Every other request is credentialed by the token, including reading the session and changing the
 * password, so its 401 is the token and is renewed like any other (#179). The backend keeps that
 * true: a wrong current password is a 403, not a 401.
 */
const NOT_RENEWED: ReadonlySet<string> = new Set(['/api/auth/sign-in', ...COOKIE_CREDENTIALED]);

/**
 * Serialises refreshes across tabs.
 *
 * Two tabs renewing with one cookie is exactly the pattern reuse detection is built to punish: the
 * second would present a token the first had already exchanged, and the backend would — correctly —
 * conclude the token had been stolen and end the session. A Web Lock makes the tabs take turns.
 */
const REFRESH_LOCK = 'hrsched.refresh';

/**
 * One in-flight refresh at a time within this tab, shared by every request waiting on it. Resolves
 * with what the cookie renewed as, or null when the cookie was refused.
 */
let refreshing: Promise<SignedIn | null> | null = null;

/**
 * The only module in the app that calls fetch, and the only one that knows a header exists.
 *
 * Keeping that in one place is what made the identity headers deletable in a single edit, and what
 * stops a page quietly inventing its own idea of who is acting. The bearer token is now the whole
 * of what says who that is.
 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  // Noted before sending, because by the time a 401 comes back the session may have ended — through
  // another request's renewal, or another tab — and nothing held would read as a restore, which
  // adopts whoever the cookie belongs to (#250). A request sent as nobody has no session to renew.
  const sentAs = currentSession();
  const first = await send(path, init);
  if (first.status !== 401 || NOT_RENEWED.has(path) || sentAs === null) {
    return interpret<T>(path, first);
  }
  // One renewal, then one retry, and never more. A second 401 means the session is genuinely over,
  // and retrying past that would be a loop that looks like an outage.
  if (!(await refreshOnce(sentAs))) {
    return interpret<T>(path, first);
  }
  return interpret<T>(path, await send(path, init));
}

interface Answered {
  readonly status: number;
  readonly ok: boolean;
  readonly text: string;
  /** The media type the body was sent as, without its parameters, or null when none was named. */
  readonly mediaType: string | null;
  readonly reference: string | null;
}

async function send(path: string, init: RequestInit): Promise<Answered> {
  const headers = new Headers(init.headers);
  const token = currentAccessToken();
  if (COOKIE_CREDENTIALED.has(path)) {
    headers.set('X-Requested-With', REQUESTED_WITH);
  } else if (token !== null) {
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
    mediaType: mediaTypeOf(response.headers.get('Content-Type')),
    reference: response.headers.get(TRACE_ID_HEADER),
  };
}

function mediaTypeOf(contentType: string | null): string | null {
  return contentType === null ? null : (contentType.split(';')[0] ?? '').trim().toLowerCase();
}

function interpret<T>(path: string, answered: Answered): T {
  if (!answered.ok) {
    // A body that is not JSON is the detail only when it was sent as plain text. A gateway with no
    // backend behind it may send nothing or a page of HTML, and either is left for toApiError to
    // describe by its status: markup shown as a message would put a raw page in an alert.
    const plain = answered.mediaType === PLAIN_TEXT && answered.text.length > 0;
    throw toApiError(
      answered.status,
      parseOrNull(answered.text) ?? (plain ? { detail: answered.text } : null),
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
 * A refused renewal ends the session rather than being retried: the cookie is either good or gone.
 *
 * Only a 401 is a refusal: it is the one way the backend refuses a cookie. Anything else — a proxy
 * or gateway with no backend behind it, or the backend failing — has decided nothing about the
 * cookie, and ending the session then would tell somebody they are signed out because the network
 * or the server was down (#139). The renewal carries no access token, so the 401 cannot be the
 * backend refusing a stale one instead of the cookie (#179).
 *
 * A renewal that answers with an account other than the expected one ends the session too, rather
 * than changing who this tab is acting as. Each caller judges the shared answer against its own
 * expectation, so two requests sent as one account share one renewal and are refused together.
 *
 * @param expected the account the caller's request was sent as, or null for a restore, which asks
 *     whose the cookie is and accepts any answer
 * @returns whether there is a usable token for the expected account afterwards
 * @throws ApiError when the refresh failed without being refused, or the fetch rejection itself
 */
async function refreshOnce(expected: Session | null): Promise<boolean> {
  const renewed = await sharedRenewal();
  if (renewed === null) {
    return false;
  }
  if (expected === null) {
    holdSession(renewed);
    return true;
  }
  const stillActingAsExpected = currentSession()?.userId === expected.userId;
  // One cookie jar is shared by every tab, so the cookie renews whichever account signed in last,
  // in any tab. Adopting another account's session here would leave this tab acting as it while
  // the shell still names the old one (#250). A session that already ended, or became somebody
  // else's in this tab, is not this request's to end. Only this tab forgets: the account the cookie
  // now belongs to is live, and telling the other tabs would sign it out of every one.
  if (renewed.session.userId !== expected.userId) {
    if (stillActingAsExpected) {
      forgetSessionHere();
    }
    return false;
  }
  // The account matches, but a session that ended while the renewal was out stays ended: holding
  // the renewed token would sign back in a tab that was just signed out.
  if (!stillActingAsExpected) {
    return false;
  }
  holdSession(renewed);
  return true;
}

function sharedRenewal(): Promise<SignedIn | null> {
  refreshing ??= (async () => {
    try {
      return await underRefreshLock(async () => {
        const answered = await send('/api/auth/refresh', { method: 'POST' });
        if (answered.status === 401) {
          endSession();
          return null;
        }
        return interpret<SignedIn>('/api/auth/refresh', answered);
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
 * The authentication endpoints.
 *
 * Signing in is credentialed by the name and password in its body, renewing and signing out by the
 * refresh cookie, and reading the session and changing the password by the access token, like any
 * other call. They live here rather than in a module of their own because three of them carry a
 * credential other than the token, and this module is the only one allowed to call `fetch` at all.
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
   * @returns whether a session was restored; false when the backend refused the cookie
   * @throws ApiError when the refresh failed without being refused, such as a gateway's 502
   * @throws TypeError, as `fetch` rejects, when the backend could not be reached at all
   */
  restore: (): Promise<boolean> => refreshOnce(null),

  /**
   * Ends the session here and at the backend. Always forgets locally, even if the request fails:
   * somebody who asked to sign out must not be left signed in because the network was down.
   *
   * Every tab forgets its token before the request is sent, as a sign-out not yet confirmed, and
   * is told the session ended only once the backend answers. The request is credentialed by the
   * cookie, so nothing needs the token by then.
   *
   * Sent with `keepalive`, so a sign-out clicked just before the tab closes is not aborted as the
   * page unloads: the backend clears the refresh cookie only when the sign-out reaches it, and a
   * cookie left behind restores the session on the next load (#250).
   *
   * @throws ApiError when the backend did not confirm the sign-out, and the fetch rejection itself
   *     when it was not reached; either way the refresh cookie may still be live, and every tab is
   *     left saying so
   */
  signOut: async (): Promise<void> => {
    beginSignOut();
    await request<void>('/api/auth/sign-out', { method: 'POST', keepalive: true });
    endSession();
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
