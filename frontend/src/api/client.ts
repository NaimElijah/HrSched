import { toApiError } from './problem';

/**
 * Who the caller claims to be.
 *
 * There is no authentication in HrSched: the backend takes the acting identity from these headers
 * and believes it. This type exists so that the claim is explicit in the code rather than hidden,
 * and so that deleting it when an identity provider lands is a compiler-guided change.
 */
export interface ActingIdentity {
  readonly organisation: string;
  readonly user: string;
  readonly role: Role;
  /** The personnel record this user is, when they are one. Sent as X-Employee. */
  readonly employee: string | null;
}

export const ROLES = ['EMPLOYEE', 'SCHEDULER', 'ADMIN', 'OPERATOR'] as const;
export type Role = (typeof ROLES)[number];

/**
 * The only module in the app that calls fetch, and the only one that knows a header exists.
 *
 * Keeping that in one place is what makes the identity headers deletable in a single edit, and
 * stops a page quietly inventing its own idea of who is acting.
 */
async function request<T>(
  identity: ActingIdentity,
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('X-Organisation', identity.organisation);
  headers.set('X-User', identity.user);
  headers.set('X-Role', identity.role);
  if (identity.employee) {
    headers.set('X-Employee', identity.employee);
  }
  if (init.body !== undefined) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(path, { ...init, headers });

  if (!response.ok) {
    throw toApiError(response.status, await readBody(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await readBody(response)) as T;
}

async function readBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text.length === 0) {
    return null;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    // A non-JSON body is still information; surfacing it beats reporting a parse failure.
    return { detail: text };
  }
}

/**
 * A stable string identifying who is acting.
 *
 * Every response depends on all four claims, so a change to any of them invalidates every answer
 * already on screen. Used as the reload key by useAsync.
 */
export function identityKey(identity: ActingIdentity): string {
  return `${identity.organisation}|${identity.user}|${identity.role}|${identity.employee ?? ''}`;
}

export const api = {
  get: <T>(identity: ActingIdentity, path: string): Promise<T> => request<T>(identity, path),

  post: <T>(identity: ActingIdentity, path: string, body?: unknown): Promise<T> =>
    request<T>(identity, path, {
      method: 'POST',
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    }),

  put: <T>(identity: ActingIdentity, path: string, body?: unknown): Promise<T> =>
    request<T>(identity, path, {
      method: 'PUT',
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    }),
};
