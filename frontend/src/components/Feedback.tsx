import { ApiError } from '../api/problem';

/**
 * Shows the trace id of the request that was refused, when the backend sent one.
 *
 * Every log line that request produced carries the same value, so this turns "it broke this
 * morning" into something findable. It is shown quietly: it is for reporting a problem, not for
 * understanding one.
 */
function Reference({ of }: { of: ApiError }) {
  return of.reference === null ? null : <p className="reference">Reference: {of.reference}</p>;
}

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
        <Reference of={error} />
      </div>
    );
  }
  if (error instanceof ApiError && error.isRefused) {
    return (
      <div className="failure" role="alert">
        <strong>Refused.</strong> {error.message}
        <Reference of={error} />
      </div>
    );
  }
  if (error instanceof ApiError && error.isUnauthenticated) {
    return (
      <div className="failure" role="alert">
        <strong>Your session has ended.</strong> Sign in again.
        <Reference of={error} />
      </div>
    );
  }
  if (error instanceof ApiError && error.isForbidden) {
    return (
      <div className="failure" role="alert">
        <strong>Your role may not do this.</strong> {error.message}
        <Reference of={error} />
      </div>
    );
  }
  if (error instanceof ApiError && error.isNotFound) {
    return (
      <div className="failure" role="alert">
        <strong>Nothing here for this organisation.</strong> A cross-tenant attempt answers 404
        rather than 403, so this may also mean it exists for somebody else.
        <Reference of={error} />
      </div>
    );
  }
  if (error instanceof ApiError && error.isConflict) {
    return (
      <div className="failure" role="alert">
        {/*
          The heading invents nothing, because a 409 is no longer only a race. It is also a
          sign-in name somebody else holds, an employee who already has an account, and a change
          that would leave an organisation with no Admin. The backend writes a specific, actionable
          sentence for each, and that sentence is the message below.
        */}
        <strong>That cannot be done.</strong> {error.message}
        <Reference of={error} />
      </div>
    );
  }
  return (
    <div className="failure" role="alert">
      {error instanceof Error ? error.message : String(error)}
      {error instanceof ApiError ? <Reference of={error} /> : null}
    </div>
  );
}

/**
 * Says a request is in flight, from a live region that is present even while there is nothing to
 * say.
 *
 * A live region is announced when its content changes, so one that only appeared along with its
 * text would be skipped by a screen reader.
 */
export function Busy({ busy }: { busy: boolean }) {
  return (
    <p className="busy" role="status">
      {busy ? 'Working…' : null}
    </p>
  );
}
