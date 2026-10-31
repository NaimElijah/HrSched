/**
 * The failure shapes the backend returns, and the one error type the rest of the app sees.
 *
 * Every response body here was read off a live backend before this file was written; none of it is
 * inferred from the Java. The backend answers with RFC 9457 problem details.
 */

/**
 * One rule a roster would break, and for whom: a working-time rule, or one that holds whatever
 * those say, such as one place at a time (ADR-042).
 */
export interface Violation {
  readonly employee: string;
  readonly constraint: string;
  readonly detail: string;
}

/** The type of a problem detail that names none (RFC 9457 section 3.1.1). */
const ABOUT_BLANK = 'about:blank';

/** The media type of a problem detail (RFC 9457 section 3), the only body whose `type` is one. */
const PROBLEM_JSON = 'application/problem+json';

/**
 * A request the backend refused.
 *
 * Carries the status so callers can distinguish the cases that mean different things: a 422 listing
 * violations is a roster that breaks the rules, a 422 listing none is some other thing the domain
 * would not do, 403 is a role that may not do this, 404 is a resource that does not
 * exist for this tenant — and, because tenancy answers 404 rather than 403, possibly one that
 * exists for somebody else.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly violations: readonly Violation[];
  /**
   * The problem's type URI, one per kind of refusal (ADR-057), or `about:blank` when the body
   * named none, as RFC 9457 section 3.1.1 reads an absent type, or was not a problem detail at all.
   * Kept, not yet branched on.
   */
  readonly type: string;
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
    type: string = ABOUT_BLANK,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.violations = violations;
    this.reference = reference;
    this.type = type;
  }

  /**
   * True when a roster was refused for the rules it breaks, every one listed: working-time rules,
   * and those that hold whatever they say, such as one place at a time (ADR-042).
   *
   * Decided by the violations rather than the status alone: the backend also answers 422 when one
   * assignment made by hand is refused - an assignee without the shift's qualification, a shift
   * outside the roster's period, somebody who would be in two places at once - and those list
   * nothing, naming their one reason in the detail instead.
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

  /**
   * True when the request clashes with something already settled: a swap somebody else decided, one
   * that lapsed with its roster, a shift that already has a swap waiting, or a roster whose status
   * forbids the change — most often a draft somebody published first.
   */
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
 * parsed out. So does the media type, lower-cased and without parameters: a `type` names a kind of
 * refusal only in an `application/problem+json` body, and in any other JSON, such as a gateway's, it
 * is somebody else's field and is not kept.
 */
export function toApiError(
  status: number,
  body: unknown,
  reference: string | null = null,
  mediaType: string | null = null,
): ApiError {
  if (typeof body !== 'object' || body === null) {
    return new ApiError(status, `The server returned ${status}.`, [], reference);
  }
  const problem = body as Record<string, unknown>;
  const detail = typeof problem.detail === 'string' ? problem.detail : undefined;
  const title = typeof problem.title === 'string' ? problem.title : undefined;
  const type =
    mediaType === PROBLEM_JSON && typeof problem.type === 'string' ? problem.type : ABOUT_BLANK;
  const violations = Array.isArray(problem.violations)
    ? problem.violations.filter(isViolation)
    : [];
  return new ApiError(
    status,
    detail ?? title ?? `The server returned ${status}.`,
    violations,
    reference,
    type,
  );
}
