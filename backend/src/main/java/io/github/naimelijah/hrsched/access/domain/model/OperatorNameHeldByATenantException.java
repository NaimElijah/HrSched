package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Raised when the sign-in name configured for the platform operator already belongs to a tenant
 * account.
 *
 * <p>No operator can be created under that name, because sign-in names are unique across the
 * platform. Reporting the operator as already present would be false, and a startup log saying all
 * is well would hide a configuration that names somebody else's account. So the bootstrap fails
 * instead.
 *
 * <p>A tenant exists only once an operator has onboarded it, so an operator almost certainly exists
 * already, under another name. The fix is to configure that operator's sign-in name, or to stop
 * configuring an operator to bootstrap - not to pick a new free name, which would create a second
 * operator.
 *
 * <p>Names the holding account by its identity and never by its sign-in name. The sign-in name is a
 * person's, and this message ends up in a startup log.
 */
public class OperatorNameHeldByATenantException extends RuntimeException {

  private final UserId holder;

  /**
   * @param holder the tenant account that signs in under the configured name
   */
  public OperatorNameHeldByATenantException(UserId holder) {
    super(
        ("The sign-in name configured for the platform operator belongs to tenant account %s, so"
                + " no operator was created under it. A tenant exists only once an operator has"
                + " onboarded it, so one most likely exists already: configure that operator's"
                + " sign-in name, or configure no operator to bootstrap")
            .formatted(holder.value()));
    this.holder = holder;
  }

  /**
   * @return the tenant account that signs in under the configured name
   */
  public UserId holder() {
    return holder;
  }
}
