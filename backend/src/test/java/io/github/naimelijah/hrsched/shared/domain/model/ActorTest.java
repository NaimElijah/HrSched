package io.github.naimelijah.hrsched.shared.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
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

  /**
   * The operator belongs to no organisation, so there is none whose data it may act on. This test
   * asserted the opposite until ADR-030: {@code mayAccess} returning true was what let the operator
   * skip every role check in every tenant.
   */
  @Test
  void GivenTheOperator_WhenRequiringAccessToAnyOrganisation_ThenItIsRefusedAsNotAMember() {
    Actor operator = new Operator(UserId.newId());

    assertThat(operator.mayAccess(ACME)).isFalse();
    assertThat(operator.mayAccess(OTHER)).isFalse();
    assertThatThrownBy(() -> operator.requireAccessTo(ACME))
        .isInstanceOf(NotATenantMemberException.class);
  }

  /**
   * The guard hands back the member it proved, so the next line has something to ask a role of.
   * Before ADR-030 it returned nothing, and callers reached for the role with an {@code instanceof}
   * that silently skipped an operator.
   */
  @Test
  void GivenATenantActor_WhenRequiringAccessToTheirOwnOrganisation_ThenTheyAreReturned() {
    TenantActor member = new TenantActor(UserId.newId(), ACME, Role.ADMIN);

    assertThat(member.requireAccessTo(ACME)).isSameAs(member);
  }

  @Test
  void GivenATenantActor_WhenRequiringAccessToAnotherOrganisation_ThenItIsACrossTenantAttempt() {
    TenantActor member = new TenantActor(UserId.newId(), ACME, Role.ADMIN);

    assertThatThrownBy(() -> member.requireAccessTo(OTHER))
        .isInstanceOf(CrossTenantAccessException.class);
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
  void GivenATenantActor_WhenRequiringTenantMembership_ThenTheyAreReturned() {
    Actor member = new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE);

    assertThat(member.requireTenantMembership()).isSameAs(member);
  }

  @Test
  void GivenTheOperator_WhenRequiringTenantMembership_ThenItIsRefusedAsNotAMember() {
    Actor operator = new Operator(UserId.newId());

    assertThatThrownBy(operator::requireTenantMembership)
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenAnActorLinkedToAnEmployee_WhenAskedIfTheyAreThatEmployee_ThenTheyAre() {
    EmployeeId themself = EmployeeId.newId();
    TenantActor actor = new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(themself));

    assertThat(actor.isThemself(themself)).isTrue();
  }

  @Test
  void GivenAnActorLinkedToAnEmployee_WhenAskedIfTheyAreAnotherEmployee_ThenTheyAreNot() {
    TenantActor actor =
        new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(EmployeeId.newId()));

    assertThat(actor.isThemself(EmployeeId.newId())).isFalse();
  }

  /**
   * An account linked to nobody is nobody's own record, even when its user id and an employee id
   * carry the same UUID: the two identities are never compared (ADR-012).
   */
  @Test
  void GivenAnActorLinkedToNobody_WhenAskedIfTheyAreAnEmployeeWithTheirOwnUuid_ThenTheyAreNot() {
    UserId id = UserId.newId();
    TenantActor actor = new TenantActor(id, ACME, Role.SCHEDULER);

    assertThat(actor.isThemself(new EmployeeId(id.value()))).isFalse();
  }

  @Test
  void GivenTheSetOfActors_WhenListingThem_ThenOnlyOperatorCrossesOrganisations() {
    assertThat(Actor.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(TenantActor.class, Operator.class);
  }
}
