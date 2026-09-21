package io.github.naimelijah.hrsched.access.application.port.out;

/**
 * The access changes worth a permanent record.
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
