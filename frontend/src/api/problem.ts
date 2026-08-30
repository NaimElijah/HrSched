/**
 * The failure shapes the backend returns, and the one error type the rest of the app sees.
 *
 * Every response body here was read off a live backend before this file was written; none of it is
 * inferred from the Java. The backend answers with RFC 9457 problem details.
 */

/** One working-time rule a roster would break, and for whom. */
export interface Violation {
  readonly employee: string;
  readonly constraint: string;
  readonly detail: string;
}

/** An RFC 9457 problem detail, plus the `violations` the scheduling context adds on a 422. */
export interface ProblemDetail {
  readonly title?: string;
  readonly detail?: string;
  readonly status?: number;
  readonly violations?: readonly Violation[];
}

/**
 * A request the backend refused.
 *
 * Carries the status so callers can distinguish the cases that mean different things: 422 is a
 * roster that breaks the rules, 403 is a role that may not do this, 404 is a resource that does not
 * exist for this tenant — and, because tenancy answers 404 rather than 403, possibly one that
 * exists for somebody else.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly violations: readonly Violation[];

  constructor(status: number, message: string, violations: readonly Violation[] = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.violations = violations;
  }

  /** True when the refusal was the working-time rules rather than the request. */
  get isRuleBreach(): boolean {
    return this.status === 422;
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

function isViolation(value: unknown): value is Violation {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.employee === 'string' &&
    typeof candidate.constraint === 'string' &&
    typeof candidate.detail === 'string'
  );
}

/**
 * Turns whatever a failed response carried into an ApiError.
 *
 * Validates rather than casts: this is the boundary where untyped data enters, and a body that is
 * not the shape we expect must not be waved through as though it were.
 */
export function toApiError(status: number, body: unknown): ApiError {
  if (typeof body !== 'object' || body === null) {
    return new ApiError(status, `The server returned ${status}.`);
  }
  const problem = body as Record<string, unknown>;
  const detail = typeof problem.detail === 'string' ? problem.detail : undefined;
  const title = typeof problem.title === 'string' ? problem.title : undefined;
  const violations = Array.isArray(problem.violations)
    ? problem.violations.filter(isViolation)
    : [];
  return new ApiError(status, detail ?? title ?? `The server returned ${status}.`, violations);
}
