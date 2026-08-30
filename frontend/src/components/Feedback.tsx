import { ApiError } from '../api/problem';

/**
 * Renders whatever went wrong, distinguishing the refusals that mean different things.
 *
 * A rule breach is not an error in the usual sense: the request was well formed and the rules said
 * no. It gets the violation list, because a scheduler told only "rejected" cannot act on it.
 */
export function Failure({
  error,
  nameOf,
}: {
  error: unknown;
  /** Resolves an employee id to a name. Without it a scheduler is shown a UUID to act on. */
  nameOf?: (employee: string) => string | undefined;
}) {
  if (error === null || error === undefined) {
    return null;
  }
  if (error instanceof ApiError && error.isRuleBreach) {
    return (
      <div className="failure failure--rules" role="alert">
        <strong>Refused: the roster breaks the working-time rules.</strong>
        <p>There is no override. Cover means publishing a roster that is itself legal.</p>
        <ul>
          {error.violations.map((violation, index) => (
            <li key={index}>
              <strong>{nameOf?.(violation.employee) ?? violation.employee}</strong> —{' '}
              {violation.constraint}: {violation.detail}
            </li>
          ))}
        </ul>
      </div>
    );
  }
  if (error instanceof ApiError && error.isForbidden) {
    return (
      <div className="failure" role="alert">
        <strong>Your role may not do this.</strong> {error.message}
      </div>
    );
  }
  if (error instanceof ApiError && error.isNotFound) {
    return (
      <div className="failure" role="alert">
        <strong>Nothing here for this organisation.</strong> A cross-tenant attempt answers 404
        rather than 403, so this may also mean it exists for somebody else.
      </div>
    );
  }
  return (
    <div className="failure" role="alert">
      {error instanceof Error ? error.message : String(error)}
    </div>
  );
}

export function Busy({ busy }: { busy: boolean }) {
  return busy ? <p className="busy">Working…</p> : null;
}
