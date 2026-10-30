package io.github.naimelijah.hrsched.architecture.actors;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/** Breaks the actor rule by calling a {@code TenantActor} constructor outside the account. */
public final class BuildsATenantActor {

  /** The call the rule must reject. */
  public TenantActor actAsAnAdmin(OrganisationId organisation) {
    return new TenantActor(UserId.newId(), organisation, Role.ADMIN);
  }
}
