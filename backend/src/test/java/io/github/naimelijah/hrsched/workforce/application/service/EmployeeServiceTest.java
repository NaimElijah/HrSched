package io.github.naimelijah.hrsched.workforce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.workforce.application.port.in.AddEmployeeCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.DeclareUnavailabilityCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.GrantQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.RosterableEmployee;
import io.github.naimelijah.hrsched.workforce.application.port.out.EmployeeRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.domain.model.Employee;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownEmployeeException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownQualificationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Specification of the employee use cases, driven without Spring.
 *
 * <p>The isolation tests here are the ones whose failure would be a security incident rather than a
 * bug. They assert that an actor of one organisation cannot read, write, or even learn of the
 * existence of another organisation's data.
 */
class EmployeeServiceTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final TimeRange MONDAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));

  private final StubEmployeeRepository employees = new StubEmployeeRepository();
  private final StubQualificationRepository qualifications = new StubQualificationRepository();
  private final EmployeeService service = new EmployeeService(employees, qualifications);

  private static TenantActor admin(OrganisationId organisation) {
    return new TenantActor(UserId.newId(), organisation, Role.ADMIN);
  }

  @Test
  void GivenAnAdmin_WhenAddingAnEmployee_ThenTheyBelongToThatOrganisation() {
    EmployeeId id = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));

    assertThat(employees.findById(ACME, id)).isPresent();
  }

  @Test
  void GivenAnAdminOfOneOrganisation_WhenAddingToAnother_ThenItIsRefused() {
    assertThatThrownBy(
            () -> service.addEmployee(admin(ACME), new AddEmployeeCommand(RIVAL, "Mole")))
        .isInstanceOf(CrossTenantAccessException.class);

    assertThat(employees.findByOrganisation(RIVAL)).isEmpty();
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenListingRosterable_ThenNothingLeaks() {
    service.addEmployee(admin(RIVAL), new AddEmployeeCommand(RIVAL, "Their Employee"));
    service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Our Employee"));

    List<RosterableEmployee> visible = service.findRosterable(admin(ACME), ACME);

    assertThat(visible).extracting(RosterableEmployee::name).containsExactly("Our Employee");
  }

  @Test
  void GivenAnActorOfOneOrganisation_WhenListingAnother_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findRosterable(admin(ACME), RIVAL))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenActingOnThemByIdentity_ThenTheyAreUnknown() {
    EmployeeId theirs =
        service.addEmployee(admin(RIVAL), new AddEmployeeCommand(RIVAL, "Their Employee"));

    assertThatThrownBy(
            () ->
                service.declareUnavailability(
                    admin(ACME), new DeclareUnavailabilityCommand(ACME, theirs, MONDAY)))
        .describedAs("knowing an identity from another tenant must not be enough to act on it")
        .isInstanceOf(UnknownEmployeeException.class);
  }

  @Test
  void GivenAnOperator_WhenListingAnyOrganisation_ThenItIsPermitted() {
    service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    Actor operator = new Operator(UserId.newId());

    assertThat(service.findRosterable(operator, ACME)).hasSize(1);
  }

  @Test
  void GivenASchedulerRatherThanAnAdmin_WhenAddingAnEmployee_ThenItIsRefused() {
    TenantActor scheduler = new TenantActor(UserId.newId(), ACME, Role.SCHEDULER);

    assertThatThrownBy(() -> service.addEmployee(scheduler, new AddEmployeeCommand(ACME, "Ada")))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnEmployee_WhenDeclaringTheirOwnUnavailability_ThenItIsAllowed() {
    EmployeeId id = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor themselves = new TenantActor(new UserId(id.value()), ACME, Role.EMPLOYEE);

    assertThatCode(
            () ->
                service.declareUnavailability(
                    themselves, new DeclareUnavailabilityCommand(ACME, id, MONDAY)))
        .doesNotThrowAnyException();
  }

  @Test
  void GivenAnEmployee_WhenDeclaringUnavailabilityForSomeoneElse_ThenItIsRefused() {
    EmployeeId other = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor someoneElse = new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE);

    assertThatThrownBy(
            () ->
                service.declareUnavailability(
                    someoneElse, new DeclareUnavailabilityCommand(ACME, other, MONDAY)))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAQualificationOfAnotherOrganisation_WhenGrantingIt_ThenItIsUnknown() {
    EmployeeId ours = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    QualificationId theirs = QualificationId.newId();
    qualifications.save(new Qualification(theirs, RIVAL, "Their Ticket"));

    assertThatThrownBy(
            () ->
                service.grantQualification(
                    admin(ACME), new GrantQualificationCommand(ACME, ours, theirs)))
        .describedAs("a qualification defined by another tenant must not be grantable here")
        .isInstanceOf(UnknownQualificationException.class);
  }

  @Test
  void GivenAnEmployeeWithUnavailability_WhenPublished_ThenItTravelsWithThem() {
    EmployeeId id = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    service.declareUnavailability(admin(ACME), new DeclareUnavailabilityCommand(ACME, id, MONDAY));

    RosterableEmployee published = service.findRosterable(admin(ACME), ACME).getFirst();

    assertThat(published.isAvailableFor(MONDAY)).isFalse();
  }

  /** In-memory stand-in for the driven port, scoped by organisation exactly as the real one is. */
  private static final class StubEmployeeRepository implements EmployeeRepository {

    private final Map<EmployeeId, Employee> saved = new HashMap<>();

    @Override
    public void save(Employee employee) {
      saved.put(employee.id(), employee);
    }

    @Override
    public Optional<Employee> findById(OrganisationId organisation, EmployeeId id) {
      return Optional.ofNullable(saved.get(id))
          .filter(employee -> employee.organisation().equals(organisation));
    }

    @Override
    public List<Employee> findByOrganisation(OrganisationId organisation) {
      List<Employee> matching = new ArrayList<>(saved.values());
      matching.removeIf(employee -> !employee.organisation().equals(organisation));
      matching.sort(Comparator.comparing(Employee::name));
      return List.copyOf(matching);
    }
  }

  /** In-memory stand-in for the qualification port. */
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
