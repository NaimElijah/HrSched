package io.github.naimelijah.hrsched.access.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.access.application.port.in.OnboardOrganisationCommand;
import io.github.naimelijah.hrsched.access.application.port.in.PublishedOrganisation;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.access.application.port.out.OrganisationRepository;
import io.github.naimelijah.hrsched.access.domain.model.InvalidOrganisationNameException;
import io.github.naimelijah.hrsched.access.domain.model.NotThePlatformOperatorException;
import io.github.naimelijah.hrsched.access.domain.model.Organisation;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Specification of the organisation use cases, driven without Spring.
 *
 * <p>Onboarding and listing tenants are the platform operator's alone, and the check that enforces
 * it is the inverse of every other one in this codebase: not which role a tenant user holds, but
 * whether they belong to a tenant at all. A tenant Admin is refused however privileged they are
 * inside their own organisation, and that is the test whose failure would let one customer create
 * or enumerate others.
 *
 * <p>Reading a single organisation is the exception, and it is where the tenant boundary applies
 * normally.
 *
 * <p>Ordering is deliberately not asserted here. Sorting is the persistence adapter's promise, and
 * is asserted against the real adapter in {@code JdbcOrganisationRepositoryTest}; a stub that
 * sorted would only prove the stub sorts.
 */
class OrganisationServiceTest {

  private final StubOrganisationRepository organisations = new StubOrganisationRepository();
  private final RecordingAuditTrail audit = new RecordingAuditTrail();
  private final OrganisationService service = new OrganisationService(organisations, audit);

  private static Operator operator() {
    return new Operator(UserId.newId());
  }

  private static TenantActor actor(OrganisationId organisation, Role role) {
    return new TenantActor(UserId.newId(), organisation, role);
  }

  @Test
  void GivenTheOperator_WhenOnboardingAnOrganisation_ThenItExistsUnderAFreshIdentity() {
    OrganisationId id =
        service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Acme"));

    assertThat(organisations.findById(id)).isPresent();
    assertThat(service.isKnown(id)).isTrue();
  }

  @Test
  void GivenTwoOrganisationsOfTheSameName_WhenOnboarded_ThenTheyAreStillDifferentTenants() {
    OrganisationId first =
        service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Acme"));
    OrganisationId second =
        service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Acme"));

    assertThat(first).isNotEqualTo(second);
    assertThat(service.findOrganisations(operator())).hasSize(2);
  }

  @Test
  void GivenAnAdminRole_WhenOnboardingAnOrganisation_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.onboardOrganisation(
                    actor(OrganisationId.newId(), Role.ADMIN),
                    new OnboardOrganisationCommand("Acme")))
        .isInstanceOf(NotThePlatformOperatorException.class);
  }

  @Test
  void GivenASchedulerRole_WhenOnboardingAnOrganisation_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.onboardOrganisation(
                    actor(OrganisationId.newId(), Role.SCHEDULER),
                    new OnboardOrganisationCommand("Acme")))
        .isInstanceOf(NotThePlatformOperatorException.class);
  }

  @Test
  void GivenAnEmployeeRole_WhenOnboardingAnOrganisation_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.onboardOrganisation(
                    actor(OrganisationId.newId(), Role.EMPLOYEE),
                    new OnboardOrganisationCommand("Acme")))
        .isInstanceOf(NotThePlatformOperatorException.class);
  }

  @Test
  void GivenABlankName_WhenOnboarding_ThenNothingIsSaved() {
    assertThatThrownBy(
            () -> service.onboardOrganisation(operator(), new OnboardOrganisationCommand(" ")))
        .isInstanceOf(InvalidOrganisationNameException.class);

    assertThat(service.findOrganisations(operator())).isEmpty();
  }

  @Test
  void GivenSeveralOrganisations_WhenTheOperatorLists_ThenEveryTenantIsReturned() {
    service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Acme"));
    service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Rival"));

    assertThat(service.findOrganisations(operator()))
        .extracting(PublishedOrganisation::name)
        .containsExactlyInAnyOrder("Acme", "Rival");
  }

  /**
   * The register of tenants is the one collection no tenant may read: it names every other customer
   * on the platform. A failure here is a disclosure of the customer list, not a bug.
   */
  @Test
  void GivenAnAdminOfOneOrganisation_WhenListingOrganisations_ThenItIsRefused() {
    OrganisationId acme =
        service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Acme"));
    service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Rival"));

    assertThatThrownBy(() -> service.findOrganisations(actor(acme, Role.ADMIN)))
        .isInstanceOf(NotThePlatformOperatorException.class);
  }

  @Test
  void GivenAnOrganisation_WhenItsOwnAdminReadsIt_ThenItIsReturned() {
    OrganisationId acme =
        service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Acme"));

    assertThat(service.findOrganisation(actor(acme, Role.ADMIN), acme))
        .contains(new PublishedOrganisation(acme, "Acme"));
  }

  @Test
  void GivenAnOrganisation_WhenItsOwnEmployeeReadsIt_ThenItIsReturned() {
    OrganisationId acme =
        service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Acme"));

    assertThat(service.findOrganisation(actor(acme, Role.EMPLOYEE), acme)).isPresent();
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenReadingOurs_ThenItIsRefused() {
    OrganisationId acme =
        service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Acme"));
    OrganisationId rival =
        service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Rival"));

    assertThatThrownBy(() -> service.findOrganisation(actor(rival, Role.ADMIN), acme))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenTheOperator_WhenReadingAnyOrganisation_ThenItIsPermitted() {
    OrganisationId acme =
        service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Acme"));

    assertThat(service.findOrganisation(operator(), acme)).isPresent();
  }

  @Test
  void GivenNoSuchOrganisation_WhenTheOperatorReadsIt_ThenNothingIsReturned() {
    assertThat(service.findOrganisation(operator(), OrganisationId.newId())).isEmpty();
  }

  /**
   * The question the boundary asks on every request (ADR-020). It answers about existence only, and
   * takes no actor, because it runs before an actor built from an unverified claim means anything.
   */
  @Test
  void GivenAnOrganisationNobodyOnboarded_WhenAskedWhetherItIsKnown_ThenItIsNot() {
    service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Acme"));

    assertThat(service.isKnown(OrganisationId.newId())).isFalse();
  }

  @Test
  void GivenNoOrganisationAtAll_WhenAskedWhetherOneIsKnown_ThenItIsNot() {
    assertThat(service.isKnown(OrganisationId.newId())).isFalse();
  }

  @Test
  void GivenNull_WhenAskedWhetherItIsKnown_ThenItIsRejectedRatherThanTreatedAsUnknown() {
    assertThatThrownBy(() -> service.isKnown(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenTheOperator_WhenOnboarding_ThenTheChangeIsRecordedAgainstThem() {
    Operator operator = operator();

    OrganisationId onboarded =
        service.onboardOrganisation(operator, new OnboardOrganisationCommand("Acme"));

    assertThat(audit.of(AccessChange.ORGANISATION_ONBOARDED))
        .containsExactly(
            new AuditedChange(
                Optional.of(onboarded),
                operator.id(),
                AccessChange.ORGANISATION_ONBOARDED,
                onboarded.value()));
  }

  @Test
  void GivenAnAdminRole_WhenOnboardingIsRefused_ThenNothingIsRecorded() {
    assertThatThrownBy(
            () ->
                service.onboardOrganisation(
                    actor(OrganisationId.newId(), Role.ADMIN),
                    new OnboardOrganisationCommand("Acme")))
        .isInstanceOf(NotThePlatformOperatorException.class);

    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenABlankName_WhenOnboardingFails_ThenNoChangeIsRecordedForIt() {
    assertThatThrownBy(
            () -> service.onboardOrganisation(operator(), new OnboardOrganisationCommand(" ")))
        .isInstanceOf(InvalidOrganisationNameException.class);

    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenAnOrganisation_WhenItIsMerelyRead_ThenNothingIsRecorded() {
    OrganisationId acme =
        service.onboardOrganisation(operator(), new OnboardOrganisationCommand("Acme"));

    service.findOrganisation(operator(), acme);
    service.findOrganisations(operator());
    service.isKnown(acme);

    assertThat(audit.changes())
        .describedAs("reads are not audited, or the writes would be buried under them")
        .containsExactly(AccessChange.ORGANISATION_ONBOARDED);
  }

  /** Hand-written, like every other double here: the repository is our own port. */
  private static final class StubOrganisationRepository implements OrganisationRepository {

    private final Map<OrganisationId, Organisation> saved = new HashMap<>();

    @Override
    public void save(Organisation organisation) {
      saved.put(organisation.id(), organisation);
    }

    @Override
    public Optional<Organisation> findById(OrganisationId id) {
      return Optional.ofNullable(saved.get(id));
    }

    /**
     * Deliberately unsorted: ordering is the real adapter's promise, and is asserted against it.
     */
    @Override
    public List<Organisation> findAll() {
      return List.copyOf(saved.values());
    }
  }
}
