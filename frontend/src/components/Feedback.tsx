import { ApiError } from '../api/problem';
import type { Violation } from '../api/problem';

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
 * Every rule a roster breaks, one per line, each naming who and how.
 *
 * Shared by a publication refusal and a draft's check, because the backend lists both from one
 * judgement (ADR-042): showing them alike is what lets a scheduler trust that the check means what
 * publishing will say.
 */
export function Violations({
  violations,
  nameOf,
}: {
  violations: readonly Violation[];
  /** Resolves an employee id to a name. Without it a scheduler is shown a UUID to act on. */
  nameOf?: ((employee: string) => string | undefined) | undefined;
}) {
  return (
    <ul>
      {violations.map((violation, index) => (
        <li key={index}>
          <strong>{nameOf?.(violation.employee) ?? violation.employee}</strong> —{' '}
          {violation.constraint}: {violation.detail}
        </li>
      ))}
    </ul>
  );
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
        <strong>Refused: the roster breaks the rules.</strong>
        <p>There is no override. Cover means publishing a roster that is itself legal.</p>
        <Violations violations={error.violations} nameOf={nameOf} />
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
  if (error instanceof ApiError && error.isPasswordChangeRequired) {
    return (
      <div className="failure" role="alert">
        {/*
          Its way on is its own, not a colleague's. The heading names the state and leaves the
          instruction to the backend's detail, which gives it; saying it here too read it twice.
        */}
        <strong>Password change pending.</strong> {error.message}
        <Reference of={error} />
      </div>
    );
  }
  if (error instanceof ApiError && error.isForbidden) {
    return (
      <div className="failure" role="alert">
        <strong>Not permitted.</strong> {error.message} If you need this done, ask a colleague whose
        role allows it.
        <Reference of={error} />
      </div>
    );
  }
  if (error instanceof ApiError && error.isNotFound) {
    return (
      <div className="failure" role="alert">
        {/*
          A cross-tenant attempt answers 404 rather than 403, so a response never confirms that
          something exists in another organisation, and "not found" cannot tell "no such thing"
          from "not yours". The copy says what somebody can do, and nothing about which it was.
        */}
        <strong>Nothing here for this organisation.</strong> It may have been removed, or never
        existed here. Go back to the list and try again.
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
