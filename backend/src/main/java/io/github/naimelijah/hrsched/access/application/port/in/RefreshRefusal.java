package io.github.naimelijah.hrsched.access.application.port.in;

/**
 * Why a renewal was refused.
 *
 * <p>For the log and the metric, never for the caller, exactly as {@link SignInRefusal} is. A
 * client able to tell these apart would learn whether a token it holds was ever real, which is the
 * one thing the holder of a stolen token would like to find out.
 *
 * <p>{@link #REUSED} is the one worth alerting on. It means a token was presented after it had
 * already been exchanged, so two parties hold copies of it and one of them is not its owner.
 */
public enum RefreshRefusal {

  /** No such token was ever issued, or it is no longer remembered. */
  UNKNOWN_TOKEN,

  /** The token had already been exchanged, so somebody is holding a copy of somebody else's. */
  REUSED,

  /** The token itself has expired: the session sat idle for too long. */
  EXPIRED,

  /** The session has been signed out, revoked, or has reached its absolute end. */
  SESSION_ENDED,

  /** The account has been disabled since the session began. */
  ACCOUNT_DISABLED
}
