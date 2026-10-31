import { api, path } from './client';

/** The kinds this build knows how to put into words. */
export type NotificationKind = 'SWAP_REQUESTED' | 'SWAP_APPROVED' | 'SWAP_REJECTED' | 'SWAP_LAPSED';

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
  findMine: (): Promise<Notification[]> => api.get<Notification[]>('/api/notifications'),

  /**
   * Marks one of the caller's own notifications read, and answers no content. A 404 when it is not
   * in their inbox, including when it is somebody else's, and a 403 for an account linked to no
   * employee.
   */
  markRead: (id: string): Promise<void> => api.post<void>(path`/api/notifications/${id}/read`),
};
