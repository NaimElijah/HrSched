package io.github.naimelijah.hrsched.notifications.domain.model;

/**
 * What somebody is being told about.
 *
 * <p>An enum rather than free text, and the reason is the same one that keeps a name out of the
 * audit trail: a notification carries identifiers and a kind, and the reader resolves those into
 * words. Nothing here can hold a sentence somebody wrote, so nothing here can hold personal data.
 *
 * <p>The vocabulary is owned by this context even though every value today describes something that
 * happened in {@code scheduling}. That is deliberate: a notification is a thing this context
 * publishes, and letting another context hand it arbitrary text is exactly how a notification store
 * becomes a second, unaudited copy of everybody's business.
 */
public enum NotificationKind {

  /** Somebody has asked to hand one of their shifts to the person being told. */
  SWAP_REQUESTED,

  /** A swap the person being told is party to was approved, and the roster changed. */
  SWAP_APPROVED,

  /** A swap the person being told is party to was rejected, and nothing changed. */
  SWAP_REJECTED
}
