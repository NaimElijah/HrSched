package io.github.naimelijah.hrsched.workforce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.workforce.application.port.in.DefineQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.PublishedQualification;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.domain.model.InvalidQualificationNameException;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Specification of the qualification use cases, driven without Spring.
 *
 * <p>Defining is an Admin act and reading is not gated by role at all: a Scheduler filling a shift
 * and an Employee reading their own record both need to know what the words mean. What binds both
 * is the tenant boundary, and those are the tests whose failure would be a security incident rather
 * than a bug.
 *
 * <p>Ordering is deliberately not asserted here. Sorting is the persistence adapter's promise, and
 * is asserted against the real adapter in {@code QualificationHttpTest}; a stub that sorted would
 * only prove the stub sorts.
 */
class QualificationServiceTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();

  private final StubQualificationRepository qualifications = new StubQualificationRepository();
  private final QualificationService service = new QualificationService(qualifications);

  private static TenantActor admin(OrganisationId organisation) {
    return new TenantActor(UserId.newId(), organisation, Role.ADMIN);
  }

  private static TenantActor actor(OrganisationId organisation, Role role) {
    return new TenantActor(UserId.newId(), organisation, role);
  }

  @Test
  void GivenAnAdmin_WhenDefiningAQualification_ThenItBelongsToThatOrganisation() {
    QualificationId id =
        service.defineQualification(admin(ACME), new DefineQualificationCommand(ACME, "Forklift"));

    assertThat(qualifications.findById(ACME, id)).isPresent();
    assertThat(qualifications.findById(RIVAL, id)).isEmpty();
  }

  @Test
  void GivenASchedulerRole_WhenDefiningAQualification_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.defineQualification(
                    actor(ACME, Role.SCHEDULER), new DefineQualificationCommand(ACME, "Forklift")))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnEmployeeRole_WhenDefiningAQualification_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.defineQualification(
                    actor(ACME, Role.EMPLOYEE), new DefineQualificationCommand(ACME, "Forklift")))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenDefiningAQualification_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.defineQualification(
                    admin(RIVAL), new DefineQualificationCommand(ACME, "Forklift")))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAnOperator_WhenDefiningAQualification_ThenItIsPermitted() {
    assertThatCode(
            () ->
                service.defineQualification(
                    new Operator(UserId.newId()), new DefineQualificationCommand(ACME, "Forklift")))
        .doesNotThrowAnyException();
  }

  @Test
  void GivenABlankName_WhenDefiningAQualification_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.defineQualification(admin(ACME), new DefineQualificationCommand(ACME, " ")))
        .isInstanceOf(InvalidQualificationNameException.class);
  }

  @Test
  void GivenABlankName_WhenDefiningAQualification_ThenNothingIsSaved() {
    assertThatThrownBy(
            () ->
                service.defineQualification(admin(ACME), new DefineQualificationCommand(ACME, " ")))
        .isInstanceOf(InvalidQualificationNameException.class);

    assertThat(service.findQualifications(admin(ACME), ACME)).isEmpty();
  }

  @Test
  void GivenAQualification_WhenListing_ThenItsIdAndNameArePublished() {
    QualificationId id =
        service.defineQualification(admin(ACME), new DefineQualificationCommand(ACME, "Forklift"));

    assertThat(service.findQualifications(admin(ACME), ACME))
        .containsExactly(new PublishedQualification(id, "Forklift"));
  }

  @Test
  void GivenQualificationsInTwoOrganisations_WhenListing_ThenOnlyOurOwnAreReturned() {
    service.defineQualification(admin(ACME), new DefineQualificationCommand(ACME, "Forklift"));
    service.defineQualification(admin(RIVAL), new DefineQualificationCommand(RIVAL, "Welding"));

    assertThat(service.findQualifications(admin(ACME), ACME))
        .extracting(PublishedQualification::name)
        .containsExactly("Forklift");
  }

  @Test
  void GivenAnEmployeeRole_WhenListingQualifications_ThenTheyAreReturned() {
    service.defineQualification(admin(ACME), new DefineQualificationCommand(ACME, "Forklift"));

    assertThat(service.findQualifications(actor(ACME, Role.EMPLOYEE), ACME)).hasSize(1);
  }

  @Test
  void GivenASchedulerRole_WhenListingQualifications_ThenTheyAreReturned() {
    service.defineQualification(admin(ACME), new DefineQualificationCommand(ACME, "Forklift"));

    assertThat(service.findQualifications(actor(ACME, Role.SCHEDULER), ACME)).hasSize(1);
  }

  @Test
  void GivenAnActorOfAnotherOrganisation_WhenListingQualifications_ThenItIsRefused() {
    service.defineQualification(admin(ACME), new DefineQualificationCommand(ACME, "Forklift"));

    assertThatThrownBy(() -> service.findQualifications(admin(RIVAL), ACME))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAnOperator_WhenListingAnotherOrganisation_ThenItIsPermitted() {
    service.defineQualification(admin(ACME), new DefineQualificationCommand(ACME, "Forklift"));

    assertThat(service.findQualifications(new Operator(UserId.newId()), ACME)).hasSize(1);
  }

  @Test
  void GivenNoQualifications_WhenListing_ThenNothingIsReturned() {
    assertThat(service.findQualifications(admin(ACME), ACME)).isEmpty();
  }

  /** Hand-written, like every other double here: the repository is our own port. */
  private static final class StubQualificationRepository implements QualificationRepository {

    private final Map<QualificationId, Qualification> saved = new HashMap<>();

    @Override
    public void save(Qualification qualification) {
      saved.put(qualification.id(), qualification);
    }

    @Override
    public Optional<Qualification> findById(OrganisationId organisation, QualificationId id) {
      return Optional.ofNullable(saved.get(id))
          .filter(qualification -> qualification.organisation().equals(organisation));
    }

    @Override
    public List<Qualification> findByOrganisation(OrganisationId organisation) {
      return saved.values().stream()
          .filter(qualification -> qualification.organisation().equals(organisation))
          .toList();
    }
  }
}
