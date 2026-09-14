import { identityKey, type ActingIdentity } from '../api/client';
import { scheduling } from '../api/scheduling';
import type { Shift } from '../api/scheduling';
import { useAsync } from '../components/useAsync';

/**
 * Reads the shifts with the given identities, for a screen that knows shifts only by identity — a
 * swap request, a notification about one.
 *
 * Each is read by identity because any member may do that, which is the only way somebody asked to
 * take a colleague's shift can learn when it is. The identities are de-duplicated and sorted into
 * the key, so the same shifts are not read again merely because they arrived in another order.
 *
 * @param identity who is asking
 * @param ids the shifts wanted; a repeated identity is read once
 * @returns the shifts read, by identity, and the failure of reading them, if there was one
 */
export function useShiftsById(
  identity: ActingIdentity,
  ids: readonly string[],
): { readonly byId: ReadonlyMap<string, Shift>; readonly error: unknown } {
  const wanted = [...new Set(ids)].sort();
  const shifts = useAsync(
    () => Promise.all(wanted.map((id) => scheduling.findShift(identity, id))),
    `${identityKey(identity)}|${wanted.join(',')}`,
  );
  return {
    byId: new Map((shifts.value ?? []).map((shift) => [shift.id, shift])),
    error: shifts.error,
  };
}
