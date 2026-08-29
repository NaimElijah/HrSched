package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.UUID;

/**
 * Builds the acting identity from request headers.
 *
 * <p>PLACEHOLDER, AND INSECURE BY DESIGN. Any caller can claim any role in any organisation simply
 * by setting a header. This exists only so the use cases can be exercised before an identity
 * provider is chosen, and it must be deleted the moment one is. It is not a stepping stone towards
 * a real implementation.
 *
 * <p>Deliberately duplicated in each web adapter rather than shared: it is adapter plumbing, and
 * sharing it would either put Spring in the kernel or make one context depend on the plumbing of
 * another.
 */
final class ActorFromHeaders {

  private ActorFromHeaders() {}

  /**
   * @param organisation the claimed organisation, absent for a platform operator
   * @param user the claimed user identity
   * @param role the claimed role, ignored for a platform operator
   * @return the actor the caller claims to be
   */
  static Actor resolve(String organisation, String user, String role) {
    UserId userId = new UserId(UUID.fromString(user));
    if (organisation == null || organisation.isBlank()) {
      return new Operator(userId);
    }
    return new TenantActor(
        userId,
        new OrganisationId(UUID.fromString(organisation)),
        Role.valueOf(role.toUpperCase()));
  }
}
