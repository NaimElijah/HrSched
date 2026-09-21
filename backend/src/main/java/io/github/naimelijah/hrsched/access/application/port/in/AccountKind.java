package io.github.naimelijah.hrsched.access.application.port.in;

/**
 * What kind of account signed in, as the world outside this context sees it.
 *
 * <p>The published counterpart of the sealed {@code Membership} in the domain. A caller needs to
 * know which of the two shapes it is looking at - a tenant account carries an organisation and a
 * role, an operator carries neither - without being handed the domain type that models it.
 */
public enum AccountKind {

  /** Belongs to one organisation, in one role. */
  TENANT,

  /** The platform operator, a member of no organisation. */
  OPERATOR
}
