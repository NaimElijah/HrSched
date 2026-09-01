package io.github.naimelijah.hrsched.notifications.application.port.in;

/**
 * The published vocabulary: what another context may ask this one to tell somebody about.
 *
 * <p>Separate from the domain's {@code NotificationKind} on purpose, and the separation is not
 * ceremony — the architecture rules caught its absence. A context may reference another's {@code
 * application.port.in} package and nothing else (ADR-008), so a caller reaching for the domain enum
 * fails the build, as {@code NotificationAdapter} did until this existed.
 *
 * <p>What that buys is the freedom the boundary is for: the domain may split a kind, rename one, or
 * carry a value this list does not, and no other context has to be recompiled to find out. The two
 * enums agreeing exactly today is a coincidence of youth, not a reason to have one.
 *
 * <p>It is deliberately a closed list rather than free text, for the reason {@code Notification}
 * has no message field: what a person reads is assembled where it is read, from identifiers.
 */
public enum NotifiableEvent {

  /** Somebody has asked to hand one of their shifts to the person being told. */
  SWAP_REQUESTED,

  /** A swap the person being told is party to was approved, and the roster changed. */
  SWAP_APPROVED,

  /** A swap the person being told is party to was rejected, and nothing changed. */
  SWAP_REJECTED
}
