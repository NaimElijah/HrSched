package io.github.naimelijah.hrsched.access.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;

import io.github.naimelijah.hrsched.access.application.port.in.OnboardOrganisationCommand;
import io.github.naimelijah.hrsched.access.application.port.in.PublishedOrganisation;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.access.application.port.out.OrganisationRepository;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.InvalidOrganisationNameException;
import io.github.naimelijah.hrsched.access.domain.model.NotThePlatformOperatorException;
import io.github.naimelijah.hrsched.access.domain.model.Organisation;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.PasswordRejection;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInNameTakenException;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.access.domain.model.UnacceptablePasswordException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

  /** Invented for these tests, and short enough that a shorter one is easy to write. */
  private static final int MINIMUM_PASSWORD_LENGTH = 12;

  /** Invented, and long enough to satisfy the policy above. */
  private static final String TEMPORARY_PASSWORD = "an invented temporary password";

  private final StubOrganisationRepository organisations = new StubOrganisationRepository();
  private final StubAccountRepository accounts = new StubAccountRepository();
  private final StubPasswordHasher passwords = new StubPasswordHasher();
  private final RecordingAuditTrail audit = new RecordingAuditTrail();
  private final OrganisationService service =
      new OrganisationService(
          organisations,
          accounts,
          passwords,
          new PasswordPolicy(MINIMUM_PASSWORD_LENGTH, passwords.maximumBytes()),
          audit,
          Clock.fixed(Instant.parse("2026-03-01T09:00:00Z"), ZoneOffset.UTC));

  /**
   * @param name what to call the organisation
   * @return a command onboarding it with a first Admin no other test shares
   */
  private static OnboardOrganisationCommand onboarding(String name) {
    return new OnboardOrganisationCommand(name, anInventedSignInName(), TEMPORARY_PASSWORD);
  }

  private static String anInventedSignInName() {
    return "admin-%s@example.com".formatted(UUID.randomUUID());
  }

  private static Operator operator() {
    return new Operator(UserId.newId());
  }

  private static TenantActor actor(OrganisationId organisation, Role role) {
    return new TenantActor(UserId.newId(), organisation, role);
  }

  @Test
  void GivenTheOperator_WhenOnboardingAnOrganisation_ThenItExistsUnderAFreshIdentity() {
    OrganisationId id = service.onboardOrganisation(operator(), onboarding("Acme"));

    assertThat(organisations.findById(id)).isPresent();
  }

  @Test
  void GivenTwoOrganisationsOfTheSameName_WhenOnboarded_ThenTheyAreStillDifferentTenants() {
    OrganisationId first = service.onboardOrganisation(operator(), onboarding("Acme"));
    OrganisationId second = service.onboardOrganisation(operator(), onboarding("Acme"));

    assertThat(first).isNotEqualTo(second);
    assertThat(service.findOrganisations(operator())).hasSize(2);
  }

  @Test
  void GivenAnAdminRole_WhenOnboardingAnOrganisation_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.onboardOrganisation(
                    actor(OrganisationId.newId(), Role.ADMIN), onboarding("Acme")))
        .isInstanceOf(NotThePlatformOperatorException.class);
  }

  @Test
  void GivenASchedulerRole_WhenOnboardingAnOrganisation_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.onboardOrganisation(
                    actor(OrganisationId.newId(), Role.SCHEDULER), onboarding("Acme")))
        .isInstanceOf(NotThePlatformOperatorException.class);
  }

  @Test
  void GivenAnEmployeeRole_WhenOnboardingAnOrganisation_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.onboardOrganisation(
                    actor(OrganisationId.newId(), Role.EMPLOYEE), onboarding("Acme")))
        .isInstanceOf(NotThePlatformOperatorException.class);
  }

  @Test
  void GivenABlankName_WhenOnboarding_ThenNothingIsSaved() {
    assertThatThrownBy(() -> service.onboardOrganisation(operator(), onboarding(" ")))
        .isInstanceOf(InvalidOrganisationNameException.class);

    assertThat(service.findOrganisations(operator())).isEmpty();
  }

  @Test
  void GivenSeveralOrganisations_WhenTheOperatorLists_ThenEveryTenantIsReturned() {
    service.onboardOrganisation(operator(), onboarding("Acme"));
    service.onboardOrganisation(operator(), onboarding("Rival"));

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
    OrganisationId acme = service.onboardOrganisation(operator(), onboarding("Acme"));
    service.onboardOrganisation(operator(), onboarding("Rival"));

    assertThatThrownBy(() -> service.findOrganisations(actor(acme, Role.ADMIN)))
        .isInstanceOf(NotThePlatformOperatorException.class);
  }

  @Test
  void GivenAnOrganisation_WhenItsOwnAdminReadsIt_ThenItIsReturned() {
    OrganisationId acme = service.onboardOrganisation(operator(), onboarding("Acme"));

    assertThat(service.findOrganisation(actor(acme, Role.ADMIN), acme))
        .contains(new PublishedOrganisation(acme, "Acme"));
  }

  @Test
  void GivenAnOrganisation_WhenItsOwnEmployeeReadsIt_ThenItIsReturned() {
    OrganisationId acme = service.onboardOrganisation(operator(), onboarding("Acme"));

    assertThat(service.findOrganisation(actor(acme, Role.EMPLOYEE), acme)).isPresent();
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenReadingOurs_ThenItIsRefused() {
    OrganisationId acme = service.onboardOrganisation(operator(), onboarding("Acme"));
    OrganisationId rival = service.onboardOrganisation(operator(), onboarding("Rival"));

    assertThatThrownBy(() -> service.findOrganisation(actor(rival, Role.ADMIN), acme))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenTheOperator_WhenReadingAnyOrganisation_ThenItIsPermitted() {
    OrganisationId acme = service.onboardOrganisation(operator(), onboarding("Acme"));

    assertThat(service.findOrganisation(operator(), acme)).isPresent();
  }

  @Test
  void GivenNoSuchOrganisation_WhenTheOperatorReadsIt_ThenNothingIsReturned() {
    assertThat(service.findOrganisation(operator(), OrganisationId.newId())).isEmpty();
  }

  @Test
  void GivenTheOperator_WhenOnboarding_ThenTheChangeIsRecordedAgainstThem() {
    Operator operator = operator();

    OrganisationId onboarded = service.onboardOrganisation(operator, onboarding("Acme"));

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
                    actor(OrganisationId.newId(), Role.ADMIN), onboarding("Acme")))
        .isInstanceOf(NotThePlatformOperatorException.class);

    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenABlankName_WhenOnboardingFails_ThenNoChangeIsRecordedForIt() {
    assertThatThrownBy(() -> service.onboardOrganisation(operator(), onboarding(" ")))
        .isInstanceOf(InvalidOrganisationNameException.class);

    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenAnOrganisation_WhenItIsMerelyRead_ThenNothingIsRecorded() {
    OrganisationId acme = service.onboardOrganisation(operator(), onboarding("Acme"));

    service.findOrganisation(operator(), acme);
    service.findOrganisations(operator());

    assertThat(audit.changes())
        .describedAs("reads are not audited, or the writes would be buried under them")
        .containsExactly(AccessChange.ORGANISATION_ONBOARDED, AccessChange.ACCOUNT_CREATED);
  }

  /**
   * Onboarding creates the account that will administer the organisation, and creates it needing a
   * new password: the one supplied was typed by whoever did the onboarding, so its owner has never
   * chosen it and somebody else knows it.
   */
  @Test
  void GivenTheOperator_WhenOnboarding_ThenTheFirstAdminIsCreatedAndMustChangeTheirPassword() {
    String signInName = anInventedSignInName();

    OrganisationId onboarded =
        service.onboardOrganisation(
            operator(), new OnboardOrganisationCommand("Acme", signInName, TEMPORARY_PASSWORD));

    Account admin = accounts.findBySignInName(new SignInName(signInName)).orElseThrow();
    assertThat(admin.membership())
        .isEqualTo(new TenantMembership(onboarded, Role.ADMIN, Optional.empty()));
    assertThat(admin.passwordChangeRequired()).isTrue();
  }

  @Test
  void GivenTheOperator_WhenOnboarding_ThenTheFirstAdminsPasswordIsStoredHashed() {
    String signInName = anInventedSignInName();

    service.onboardOrganisation(
        operator(), new OnboardOrganisationCommand("Acme", signInName, TEMPORARY_PASSWORD));

    assertThat(accounts.findBySignInName(new SignInName(signInName)).orElseThrow().passwordHash())
        .isEqualTo(passwords.hash(TEMPORARY_PASSWORD));
  }

  /**
   * The organisation row left behind is undone by the transaction the use case runs in, which is
   * Spring's job rather than this service's and is asserted through HTTP in {@code
   * OrganisationHttpTest}. What belongs here is that the refusal propagates and records nothing.
   */
  @Test
  void GivenASignInNameAlreadyTaken_WhenOnboarding_ThenItIsRefusedAndNothingIsRecorded() {
    String taken = anInventedSignInName();
    service.onboardOrganisation(
        operator(), new OnboardOrganisationCommand("Acme", taken, TEMPORARY_PASSWORD));
    audit.forget();

    assertThatThrownBy(
            () ->
                service.onboardOrganisation(
                    operator(), new OnboardOrganisationCommand("Rival", taken, TEMPORARY_PASSWORD)))
        .isInstanceOf(SignInNameTakenException.class);

    assertThat(audit.changes()).isEmpty();
  }

  /** Assessed before anything is stored, so this one leaves nothing behind without a rollback. */
  @Test
  void GivenATemporaryPasswordBelowThePolicy_WhenOnboarding_ThenNothingIsCreated() {
    assertThatThrownBy(
            () ->
                service.onboardOrganisation(
                    operator(),
                    new OnboardOrganisationCommand("Acme", anInventedSignInName(), "short")))
        .isInstanceOf(UnacceptablePasswordException.class);

    assertThat(service.findOrganisations(operator())).isEmpty();
    assertThat(audit.changes()).isEmpty();
  }

  /** Whoever typed it has to be told which rule they broke, or they cannot supply a better one. */
  @Test
  void GivenATemporaryPasswordBelowThePolicy_WhenOnboarding_ThenTheRuleItBrokeIsNamed() {
    assertThatThrownBy(
            () ->
                service.onboardOrganisation(
                    operator(),
                    new OnboardOrganisationCommand("Acme", anInventedSignInName(), "short")))
        .asInstanceOf(throwable(UnacceptablePasswordException.class))
        .returns(PasswordRejection.TOO_SHORT, UnacceptablePasswordException::reason);
  }

  /** The password is never in the message, whatever the message says about the rule. */
  @Test
  void GivenATemporaryPasswordBelowThePolicy_WhenRefused_ThenItIsNotInTheMessage() {
    assertThatThrownBy(
            () ->
                service.onboardOrganisation(
                    operator(),
                    new OnboardOrganisationCommand("Acme", anInventedSignInName(), "abbreviated")))
        .isInstanceOf(UnacceptablePasswordException.class)
        .hasMessageNotContaining("abbreviated");
  }

  @Test
  void
      GivenTheOperator_WhenOnboarding_ThenTheAccountIsRecordedAgainstThemUnderTheNewOrganisation() {
    Operator operator = operator();
    String signInName = anInventedSignInName();

    OrganisationId onboarded =
        service.onboardOrganisation(
            operator, new OnboardOrganisationCommand("Acme", signInName, TEMPORARY_PASSWORD));

    UserId admin = accounts.findBySignInName(new SignInName(signInName)).orElseThrow().id();
    assertThat(audit.of(AccessChange.ACCOUNT_CREATED))
        .containsExactly(
            new AuditedChange(
                Optional.of(onboarded),
                operator.id(),
                AccessChange.ACCOUNT_CREATED,
                admin.value()));
  }

  /**
   * Hand-written, like every other double here: the repository is our own port. Refuses an identity
   * already stored, as the real adapter's primary key does, rather than renaming its tenant.
   */
  private static final class StubOrganisationRepository implements OrganisationRepository {

    private final Map<OrganisationId, Organisation> saved = new HashMap<>();

    @Override
    public void save(Organisation organisation) {
      if (saved.putIfAbsent(organisation.id(), organisation) != null) {
        throw new IllegalStateException("An organisation is already stored under that identity");
      }
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
