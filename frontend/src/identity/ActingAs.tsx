import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { ROLES } from '../api/client';
import type { ActingIdentity, Role } from '../api/client';

/**
 * The identity every request claims.
 *
 * HrSched has no authentication. The backend reads who you are from request headers and believes
 * it, so this is a claim rather than a fact, and the UI says so out loud rather than presenting a
 * logged-in illusion. Everything here is deleted when an identity provider lands (ADR-011).
 */
interface ActingAsContextValue {
  readonly identity: ActingIdentity;
  readonly setOrganisation: (organisation: string) => void;
  readonly setUser: (user: string) => void;
  readonly setRole: (role: Role) => void;
  readonly setEmployee: (employee: string | null) => void;
}

const ActingAsContext = createContext<ActingAsContextValue | null>(null);

const STORAGE_KEY = 'hrsched.acting-as';

function randomUuid(): string {
  return crypto.randomUUID();
}

function isRole(value: unknown): value is Role {
  return typeof value === 'string' && (ROLES as readonly string[]).includes(value);
}

/**
 * Validates what came out of storage instead of casting it.
 *
 * localStorage is a boundary like any other: it carries data written by an older build of this app,
 * or by anything else on the origin. A stored identity missing its role would otherwise send the
 * literal string "undefined" as X-Role — the Headers API stringifies it — and every request would
 * fail with a 400 while the UI showed a perfectly normal role.
 */
function parseIdentity(raw: string): ActingIdentity | null {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const candidate = value as Record<string, unknown>;
  if (
    typeof candidate.organisation !== 'string' ||
    typeof candidate.user !== 'string' ||
    !isRole(candidate.role)
  ) {
    return null;
  }
  const employee = candidate.employee;
  return {
    organisation: candidate.organisation,
    user: candidate.user,
    role: candidate.role,
    employee: typeof employee === 'string' ? employee : null,
  };
}

function freshIdentity(): ActingIdentity {
  return { organisation: randomUuid(), user: randomUuid(), role: 'ADMIN', employee: null };
}

function store(identity: ActingIdentity): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(identity));
  } catch {
    // A browser that refuses storage still gets a working session; only persistence is lost.
  }
}

/**
 * Reads the stored identity, minting and **persisting** a fresh one when there is none.
 *
 * Persisting the fresh one is the whole point. Without it, a reload mints another random
 * organisation, and because the repositories are in-memory and organisation-scoped, everything
 * created in the previous session becomes unreachable.
 */
function initialIdentity(): ActingIdentity {
  let stored: string | null;
  try {
    stored = window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return freshIdentity();
  }
  const parsed = stored === null ? null : parseIdentity(stored);
  if (parsed !== null) {
    return parsed;
  }
  const fresh = freshIdentity();
  store(fresh);
  return fresh;
}

export function ActingAsProvider({ children }: { children: ReactNode }) {
  const [identity, setIdentity] = useState<ActingIdentity>(initialIdentity);

  const update = useCallback((next: ActingIdentity) => {
    setIdentity(next);
    store(next);
  }, []);

  const value = useMemo<ActingAsContextValue>(
    () => ({
      identity,
      setOrganisation: (organisation) => update({ ...identity, organisation }),
      setUser: (user) => update({ ...identity, user }),
      // A role change can invalidate the employee link, but never silently: an operator belongs to
      // no organisation and is emphatically not an employee.
      setRole: (role) =>
        update({ ...identity, role, employee: role === 'OPERATOR' ? null : identity.employee }),
      setEmployee: (employee) => update({ ...identity, employee }),
    }),
    [identity, update],
  );

  return <ActingAsContext.Provider value={value}>{children}</ActingAsContext.Provider>;
}

export function useActingAs(): ActingAsContextValue {
  const value = useContext(ActingAsContext);
  if (value === null) {
    throw new Error('useActingAs must be used inside an ActingAsProvider');
  }
  return value;
}

export { randomUuid };
