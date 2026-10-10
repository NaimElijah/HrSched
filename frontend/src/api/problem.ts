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
 * Carries the status so callers can distinguish the cases that mean different things: a 422 listing
 * violations is a roster that breaks the working-time rules, a 422 listing none is some other thing
 * the domain would not do, 403 is a role that may not do this, 404 is a resource that does not
 * exist for this tenant — and, because tenancy answers 404 rather than 403, possibly one that
 * exists for somebody else.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly violations: readonly Violation[];
  /**
   * The trace id the backend put on the response, or null when it sent none.
   *
   * Every log line the request produced carries the same value, so this is the one thing a person
   * looking at a refusal can quote that will actually find it again.
   */
  readonly reference: string | null;

  constructor(
    status: number,
    message: string,
    violations: readonly Violation[] = [],
    reference: string | null = null,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.violations = violations;
    this.reference = reference;
  }

  /**
   * True when the working-time rules refused it.
   *
   * Decided by the violations rather than the status alone: the backend also answers 422 for an
   * assignee without the shift's qualification, a shift outside the roster's period, or somebody
   * who would be in two places at once, and those list no rules because they broke none.
   */
  get isRuleBreach(): boolean {
    return this.status === 422 && this.violations.length > 0;
  }

  /** True when the request was well formed and the domain would not do it, for a stated reason. */
  get isRefused(): boolean {
    return this.status === 422 && this.violations.length === 0;
  }

  /**
   * True when the backend does not believe the caller is signed in.
   *
   * Distinct from forbidden: that one means "you, but not this"; this one means "who are you?" and
   * is the answer a session that has ended produces on every request afterwards.
   */
  get isUnauthenticated(): boolean {
    return this.status === 401;
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }

  /** True when somebody else already decided what the caller was trying to decide. */
  get isConflict(): boolean {
    return this.status === 409;
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
 *
 * The reference comes from a response header rather than the body, so it is passed in rather than
 * parsed out.
 */
export function toApiError(
  status: number,
  body: unknown,
  reference: string | null = null,
): ApiError {
  if (typeof body !== 'object' || body === null) {
    return new ApiError(status, `The server returned ${status}.`, [], reference);
  }
  const problem = body as Record<string, unknown>;
  const detail = typeof problem.detail === 'string' ? problem.detail : undefined;
  const title = typeof problem.title === 'string' ? problem.title : undefined;
  const violations = Array.isArray(problem.violations)
    ? problem.violations.filter(isViolation)
    : [];
  return new ApiError(
    status,
    detail ?? title ?? `The server returned ${status}.`,
    violations,
    reference,
  );
}
