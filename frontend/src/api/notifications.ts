import { api, path, type ActingIdentity } from './client';

/** The kinds this build knows how to put into words. */
export type NotificationKind = 'SWAP_REQUESTED' | 'SWAP_APPROVED' | 'SWAP_REJECTED';

/**
 * Mirrors NotificationResponse: that something happened, what it was about, and when.
 *
 * It carries no sentence, deliberately (ADR-021): the words are the reader's interface's business.
 * `kind` is a plain string rather than `NotificationKind` because a newer server may send one this
 * build has never heard of, and that has to be said rather than cast away.
 */
export interface Notification {
  readonly id: string;
  readonly kind: string;
  /** The identity of what it is about; for every kind today, a swap request. */
  readonly subject: string;
  readonly occurredAt: string;
  readonly read: boolean;
}

/**
 * The caller's own notifications: reading them and marking one read.
 *
 * There is deliberately no way to raise one. A notification is something a use case decided
 * somebody deserved to be told, not something a client can post into a colleague's inbox (ADR-021).
 */
export const notifications = {
  /** The caller's own inbox. Whose it is comes from the employee link, never from a parameter. */
  findMine: (identity: ActingIdentity): Promise<Notification[]> =>
    api.get<Notification[]>(identity, '/api/notifications'),

  markRead: (identity: ActingIdentity, id: string): Promise<void> =>
    api.post<void>(identity, path`/api/notifications/${id}/read`),
};
