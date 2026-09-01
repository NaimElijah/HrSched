/** How many days forward the default planning span reaches. Not a domain rule — a UI convenience. */
const DEFAULT_SPAN_DAYS = 14;

/**
 * Midnight *local* time on the given date, as an instant.
 *
 * Local rather than UTC because shifts are entered with `datetime-local`, which the browser reads
 * as wall-clock time. A span built from UTC midnight would silently exclude shifts the user had
 * just created — for a reader west of Greenwich, an evening shift lands on the next UTC day.
 *
 * @param date a calendar date as a native date input reports it, or the empty string
 * @returns the instant local midnight falls on, or null when the field is empty or unparseable
 */
export function startOfLocalDay(date: string): string | null {
  if (date === '') {
    return null;
  }
  const parsed = new Date(`${date}T00:00:00`);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

/**
 * The local calendar date an instant falls on, which is what a date input expects.
 *
 * @param instant an ISO instant
 * @returns the date, as `YYYY-MM-DD`, that the instant falls on where the reader is
 */
export function localDate(instant: string): string {
  const date = new Date(instant);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 10);
}

/**
 * The span the scheduling pages open on: today through a fortnight ahead, in local time.
 *
 * The clock is a parameter rather than a call to `new Date()` inside, so that the one piece of
 * this module that would otherwise be non-deterministic is decided by its caller — the same
 * discipline the backend keeps by having no implicit "now" anywhere.
 *
 * @param now the instant to treat as the present
 * @returns the default planning span, as two ISO instants
 */
export function defaultSpan(now: Date): { from: string; to: string } {
  const from = new Date(now);
  from.setHours(0, 0, 0, 0);
  const to = new Date(from);
  to.setDate(to.getDate() + DEFAULT_SPAN_DAYS);
  return { from: from.toISOString(), to: to.toISOString() };
}
