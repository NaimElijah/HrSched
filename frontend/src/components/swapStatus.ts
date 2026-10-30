import type { SwapStatus } from '../api/scheduling';

/**
 * Where a swap request has got to, in the words every screen uses for it.
 *
 * One table, so the Scheduler's queue and the employee's inbox cannot describe the same request
 * differently. A `Record` over the statuses, so a status added to the type cannot compile without
 * words of its own.
 */
const SWAP_STATUS: Record<SwapStatus, string> = {
  REQUESTED: 'waiting on a Scheduler',
  APPROVED: 'approved',
  REJECTED: 'declined',
  LAPSED: 'lapsed: its roster was replaced',
};

function isKnownStatus(status: string): status is SwapStatus {
  return Object.prototype.hasOwnProperty.call(SWAP_STATUS, status);
}

/**
 * @param status where a swap request stands, as the server sent it
 * @returns its words, or, for a status this build has never heard of, words that say so and name it
 */
export function swapStatusWording(status: string): string {
  return isKnownStatus(status)
    ? SWAP_STATUS[status]
    : `in a state this version of HrSched cannot describe (${status})`;
}
