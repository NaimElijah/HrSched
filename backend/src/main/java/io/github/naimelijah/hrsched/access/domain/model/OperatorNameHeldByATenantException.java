package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Raised when the sign-in name configured for the platform operator already belongs to a tenant
 * account.
 *
 * <p>No operator exists then, and none can be created, because sign-in names are unique across the
 * platform. Reporting the operator as already present would start a deployment that nobody can use
 * to onboard an organisation, with a startup log saying all is well. So the bootstrap fails
 * instead, and the deployment has to configure another name.
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
                + " no operator was created; configure a name no account holds")
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
