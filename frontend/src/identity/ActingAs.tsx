import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
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

function initialIdentity(): ActingIdentity {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (stored !== null) {
      return JSON.parse(stored) as ActingIdentity;
    }
  } catch {
    // A browser that refuses storage still gets a working app with a fresh identity.
  }
  return { organisation: randomUuid(), user: randomUuid(), role: 'ADMIN', employee: null };
}

export function ActingAsProvider({ children }: { children: ReactNode }) {
  const [identity, setIdentity] = useState<ActingIdentity>(initialIdentity);

  const update = useCallback((next: ActingIdentity) => {
    setIdentity(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      // Persisting is a convenience; losing it must not break the session.
    }
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
