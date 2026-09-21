import { useEffect, useRef, useState } from 'react';
import { access } from '../api/access';
import { ROLES, setActingIdentity } from '../api/client';
import type { Role } from '../api/client';
import { ApiError } from '../api/problem';
import type { Employee } from '../api/workforce';
import { useActingAs } from './ActingAs';

/**
 * The identity switcher, styled as a warning and impossible to dismiss.
 *
 * This is deliberately the loudest thing on the page. HrSched has no authentication, and a UI that
 * looked like a normal signed-in application would be lying about that. Switching role here turns
 * writes into 403s and switching organisation turns reads into 404s, so the isolation the backend
 * enforces is demonstrable rather than merely documented.
 *
 * Creating an organisation really creates one. It used to mint a random identifier locally, which
 * worked only because the backend believed any identifier it was sent; since ADR-020 an organisation
 * nobody onboarded does not exist, and every request naming one is refused. So this posts to the
 * register as the platform operator and switches to what came back.
 */
export function ActingAsBar({ employees }: { employees: readonly Employee[] }) {
  const { identity, setOrganisation, setUser, setRole, setEmployee } = useActingAs();
  const [onboarding, setOnboarding] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  async function onboard() {
    setOnboarding(true);
    setFailure(null);
    try {
      // Onboarding is the operator's act, whoever the bar is currently acting as, so this one
      // request claims OPERATOR and is put back afterwards. The organisation on the identity is
      // ignored by the backend here, which is what makes bootstrapping possible at all.
      setActingIdentity({ ...identity, role: 'OPERATOR', employee: null });
      let organisation;
      try {
        organisation = await access.onboardOrganisation(`Organisation ${new Date().toISOString()}`);
      } finally {
        setActingIdentity(identity);
      }
      setOrganisation(organisation.id);
    } catch (error) {
      setFailure(error instanceof ApiError ? error.message : 'Could not create an organisation.');
    } finally {
      setOnboarding(false);
    }
  }

  // A first visit has no organisation, and nothing works without one. Onboarding here rather than
  // in the provider keeps the request beside the error state and the disabled button that already
  // handle it. The ref stops React's development double-invoke from creating two organisations.
  const onboarded = useRef(false);
  useEffect(() => {
    if (identity.organisation === '' && !onboarded.current) {
      onboarded.current = true;
      void onboard();
    }
  });

  return (
    <aside className="acting-as" aria-label="Acting identity">
      <p className="acting-as__warning">
        <strong>No authentication.</strong> Identity is claimed in a header and believed. Anyone can
        be anyone — this is a placeholder, not a login.
      </p>
      <div className="acting-as__fields">
        <label>
          Organisation
          <input
            value={identity.organisation}
            onChange={(event) => setOrganisation(event.target.value)}
            spellCheck={false}
          />
        </label>
        <button type="button" onClick={() => void onboard()} disabled={onboarding}>
          {onboarding ? 'Creating…' : 'New organisation'}
        </button>

        <label>
          User
          <input
            value={identity.user}
            onChange={(event) => setUser(event.target.value)}
            spellCheck={false}
          />
        </label>

        <label>
          Role
          <select value={identity.role} onChange={(event) => setRole(event.target.value as Role)}>
            {ROLES.map((role) => (
              <option key={role} value={role}>
                {role}
              </option>
            ))}
          </select>
        </label>

        <label>
          Acting as employee
          <select
            value={identity.employee ?? ''}
            onChange={(event) => setEmployee(event.target.value === '' ? null : event.target.value)}
            disabled={identity.role === 'OPERATOR'}
          >
            <option value="">nobody</option>
            {employees.map((employee) => (
              <option key={employee.id} value={employee.id}>
                {employee.name}
              </option>
            ))}
            {/* An EMPLOYEE may not list colleagues, so the picker is empty for exactly the role
                that needs it. Without this the control would read "nobody" while X-Employee was
                still being sent -- the UI disagreeing with the request it makes. */}
            {identity.employee !== null &&
            !employees.some((employee) => employee.id === identity.employee) ? (
              <option value={identity.employee}>{identity.employee}</option>
            ) : null}
          </select>
        </label>
      </div>
      {failure === null ? null : (
        <p className="acting-as__warning" role="alert">
          {failure}
        </p>
      )}
    </aside>
  );
}
