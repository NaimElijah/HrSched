package io.github.naimelijah.hrsched.access.application.port.out;

/**
 * The access changes that are recorded through {@link AuditTrail}.
 *
 * <p>An enum rather than a string for the same reason its siblings are: a change that is recorded
 * is a change somebody declared, and the vocabulary is closed.
 */
public enum AccessChange {

  /** A new organisation joined the platform, creating a tenant boundary that did not exist. */
  ORGANISATION_ONBOARDED,

  /**
   * An account was created for somebody. Recorded separately from the organisation it belongs to,
   * because who may act inside a tenant is a different question from which tenants exist.
   */
  ACCOUNT_CREATED,

  /**
   * An account's role, or the person it belongs to, changed. One entry for both, because they are
   * one value: an account is somebody in a role, and changing either changes what it may do.
   */
  ACCOUNT_MEMBERSHIP_CHANGED,

  /** An account was disabled by an administrator, ending every session it held. */
  ACCOUNT_DISABLED,

  /** A disabled account was restored. */
  ACCOUNT_ENABLED,

  /**
   * An administrator gave an account a new temporary password. Distinct from {@link
   * #PASSWORD_CHANGED}: that one is somebody changing their own, this one is somebody else doing it
   * for them, and only the second is worth asking questions about.
   */
  ACCOUNT_PASSWORD_RESET,

  /**
   * The platform operator was created from configuration at startup. Recorded because it is the
   * most privileged account there is, and the one nobody signs in to create.
   */
  OPERATOR_BOOTSTRAPPED,

  /** A session began. The subject is the session, not the account: it is what can be revoked. */
  SIGNED_IN,

  /**
   * An account was locked after too many consecutive failures. Recorded because it is both a sign
   * somebody is guessing and the reason a colleague cannot get in.
   */
  ACCOUNT_LOCKED,

  /** A session ended because somebody signed out. */
  SIGNED_OUT,

  /**
   * Somebody changed their own password, which also ended every session opened under the old one.
   */
  PASSWORD_CHANGED,

  /**
   * A refresh token was presented after it had already been exchanged, so two parties held it and
   * one of them should not have. The sharpest entry in this vocabulary: it is the only one that
   * says, on its own, that something has probably been stolen.
   */
  REFRESH_TOKEN_REUSED
}
