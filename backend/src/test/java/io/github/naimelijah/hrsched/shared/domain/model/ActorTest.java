package io.github.naimelijah.hrsched.shared.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Specification of the tenant boundary.
 *
 * <p>These are the tests whose failure would be a security incident rather than a bug, so they
 * assert the boundary directly instead of trusting that callers remember to check it.
 */
class ActorTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId OTHER = OrganisationId.newId();

  @Test
  void GivenATenantActor_WhenAccessingItsOwnOrganisation_ThenItIsPermitted() {
    Actor actor = new TenantActor(UserId.newId(), ACME, Role.SCHEDULER);

    assertThat(actor.mayAccess(ACME)).isTrue();
    assertThatCode(() -> actor.requireAccessTo(ACME)).doesNotThrowAnyException();
  }

  @Test
  void GivenATenantActor_WhenAccessingAnotherOrganisation_ThenItIsRefused() {
    Actor actor = new TenantActor(UserId.newId(), ACME, Role.ADMIN);

    assertThat(actor.mayAccess(OTHER)).isFalse();
    assertThatThrownBy(() -> actor.requireAccessTo(OTHER))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAnOperator_WhenAccessingAnyOrganisation_ThenItIsPermitted() {
    Actor operator = new Operator(UserId.newId());

    assertThat(operator.mayAccess(ACME)).isTrue();
    assertThat(operator.mayAccess(OTHER)).isTrue();
  }

  @Test
  void GivenATenantActor_WhenItHoldsAPermittedRole_ThenTheOperationIsAllowed() {
    TenantActor scheduler = new TenantActor(UserId.newId(), ACME, Role.SCHEDULER);

    assertThatCode(() -> scheduler.requireRoleIn(Set.of(Role.SCHEDULER, Role.ADMIN)))
        .doesNotThrowAnyException();
  }

  @Test
  void GivenATenantActor_WhenItHoldsTheWrongRole_ThenTheOperationIsRefused() {
    TenantActor employee = new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE);

    assertThatThrownBy(() -> employee.requireRoleIn(Set.of(Role.SCHEDULER)))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenTheSetOfActors_WhenListingThem_ThenOnlyOperatorCrossesOrganisations() {
    assertThat(Actor.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(TenantActor.class, Operator.class);
  }
}
