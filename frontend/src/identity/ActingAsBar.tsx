import { ROLES } from '../api/client';
import type { Role } from '../api/client';
import type { Employee } from '../api/workforce';
import { randomUuid, useActingAs } from './ActingAs';

/**
 * The identity switcher, styled as a warning and impossible to dismiss.
 *
 * This is deliberately the loudest thing on the page. HrSched has no authentication, and a UI that
 * looked like a normal signed-in application would be lying about that. Switching role here turns
 * writes into 403s and switching organisation turns reads into 404s, so the isolation the backend
 * enforces is demonstrable rather than merely documented.
 */
export function ActingAsBar({ employees }: { employees: readonly Employee[] }) {
  const { identity, setOrganisation, setUser, setRole, setEmployee } = useActingAs();

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
        <button type="button" onClick={() => setOrganisation(randomUuid())}>
          New organisation
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
    </aside>
  );
}
