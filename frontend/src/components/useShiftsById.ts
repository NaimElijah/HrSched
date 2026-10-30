import { scheduling } from '../api/scheduling';
import type { Shift } from '../api/scheduling';
import { useAsync } from './useAsync';

/**
 * Reads the shifts with the given identities, for a screen that knows shifts only by identity — a
 * swap request, a notification about one.
 *
 * Each is read by identity because any member may do that, which is the only way somebody asked to
 * take a colleague's shift can learn when it is. The identities are de-duplicated and sorted into
 * the key, so the same shifts are not read again merely because they arrived in another order.
 *
 * The reads settle independently: a shift that cannot be read costs its own row its time, not every
 * row, and the failure is still returned to be shown.
 *
 * The session key comes from the calling screen rather than from `auth`, because `auth` already
 * draws on `components` and the two folders would otherwise depend on each other.
 *
 * @param ids the shifts wanted; a repeated identity is read once
 * @param sessionKey the caller's `useSessionKey()`, so a new session reads the shifts again
 * @returns the shifts read, by identity, and the first failure among the reads, if there was one
 */
export function useShiftsById(
  ids: readonly string[],
  sessionKey: string,
): {
  readonly byId: ReadonlyMap<string, Shift>;
  readonly error: unknown;
} {
  const wanted = [...new Set(ids)].sort();
  const reads = useAsync(
    () => Promise.allSettled(wanted.map((id) => scheduling.findShift(id))),
    `${sessionKey}|${wanted.join(',')}`,
  );
  const settled = reads.value ?? [];
  const byId = new Map<string, Shift>();
  for (const read of settled) {
    if (read.status === 'fulfilled') {
      byId.set(read.value.id, read.value);
    }
  }
  const failed = settled.find((read) => read.status === 'rejected');
  return { byId, error: reads.error ?? failed?.reason ?? null };
}
