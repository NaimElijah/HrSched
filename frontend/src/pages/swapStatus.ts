import type { SwapRequest } from '../api/scheduling';

/**
 * Where a swap request has got to, in the words every screen uses for it.
 *
 * One table, so the Scheduler's queue and the employee's inbox cannot describe the same request
 * differently.
 */
export const SWAP_STATUS: Record<SwapRequest['status'], string> = {
  REQUESTED: 'waiting on a Scheduler',
  APPROVED: 'approved',
  REJECTED: 'declined',
};
