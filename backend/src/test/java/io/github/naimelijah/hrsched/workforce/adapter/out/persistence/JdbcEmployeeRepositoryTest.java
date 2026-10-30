package io.github.naimelijah.hrsched.workforce.adapter.out.persistence;

import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.STUCK_AFTER_SECONDS;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.awaitWaitingOnALockOr;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.backendServingThisTransaction;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.refusalsOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import io.github.naimelijah.hrsched.workforce.domain.model.ContractTerms;
import io.github.naimelijah.hrsched.workforce.domain.model.Employee;
import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import io.github.naimelijah.hrsched.workforce.domain.model.ShiftPreference;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tenant predicate, the ordering, and everything an employee owns.
 *
 * <p>The port deliberately has no {@code findById(EmployeeId)} overload, so the only way to read an
 * employee is to say whose employee you think they are. These tests are what proves the adapter
 * honours that rather than merely accepting the argument.
 *
 * <p>The owned collections get their own tests here because they are the part a hand-written mapper
 * gets wrong: an employee is stored across four tables, and a save has to leave the other three
 * agreeing with the first.
 *
 * <p>Every duration below is invented to make a case pass or fail. None is taken from any
 * jurisdiction.
 */
class JdbcEmployeeRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final TimeRange A_FORTNIGHT_OFF =
      new TimeRange(Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-15T00:00:00Z"));
  private static final TimeRange A_WEEK_OFF =
      new TimeRange(Instant.parse("2026-12-20T00:00:00Z"), Instant.parse("2026-12-27T00:00:00Z"));
  private static final TimeRange MONDAY_DAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));

  private final JdbcEmployeeRepository repository =
      new JdbcEmployeeRepository(PostgresDatabase.jdbcClient());
  private final JdbcQualificationRepository qualifications =
      new JdbcQualificationRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
    PostgresDatabase.given(ACME, RIVAL);
  }

  private static Employee employee(OrganisationId organisation, String name) {
    return new Employee(EmployeeId.newId(), organisation, name);
  }

  /** A qualification has to exist before somebody can hold it: the join is a real foreign key. */
  private QualificationId aQualification(OrganisationId organisation, String name) {
    Qualification qualification = new Qualification(QualificationId.newId(), organisation, name);
    qualifications.save(qualification);
    return qualification.id();
  }

  @Test
  void GivenAnEmployee_WhenReadBackByTheirOwnOrganisation_ThenTheyAreFound() {
    Employee ada = employee(ACME, "Ada");
    repository.save(ada);

    assertThat(repository.findById(ACME, ada.id())).contains(ada);
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenReadBackByUs_ThenTheyAreNotFound() {
    Employee theirs = employee(RIVAL, "Their Employee");
    repository.save(theirs);

    assertThat(repository.findById(ACME, theirs.id())).isEmpty();
  }

  @Test
  void GivenNoSuchEmployee_WhenReadingThem_ThenNothingIsFound() {
    assertThat(repository.findById(ACME, EmployeeId.newId())).isEmpty();
  }

  @Test
  void GivenTwoOrganisations_WhenListingOne_ThenOnlyItsOwnPeopleComeBack() {
    repository.save(employee(ACME, "Ada"));
    repository.save(employee(RIVAL, "Their Employee"));

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Employee::name)
        .containsExactly("Ada");
  }

  @Test
  void GivenSeveralEmployees_WhenListingThem_ThenTheyComeBackInNameOrder() {
    repository.save(employee(ACME, "Grace"));
    repository.save(employee(ACME, "Ada"));
    repository.save(employee(ACME, "Marie"));

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Employee::name)
        .containsExactly("Ada", "Grace", "Marie");
  }

  /**
   * Two people may share a name, and a listing that orders them by name alone leaves their order to
   * the plan the database picks, so the same request could answer in either. Their identity settles
   * it. Saved in the opposite order, so insertion order cannot pass for it.
   */
  @Test
  void GivenTwoEmployeesSharingAName_WhenListingThem_ThenTheirIdentityOrdersThem() {
    // Invented identities, chosen only for which sorts first.
    EmployeeId first = new EmployeeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    EmployeeId second = new EmployeeId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    repository.save(new Employee(second, ACME, "Ada"));
    repository.save(new Employee(first, ACME, "Ada"));

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Employee::id)
        .containsExactly(first, second);
  }

  @Test
  void GivenTheSameEmployeeSavedTwice_WhenReadBack_ThenTheLaterVersionWins() {
    Employee original = employee(ACME, "Ada");
    repository.save(original);
    repository.save(new Employee(original.id(), ACME, "Ada Lovelace"));

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Employee::name)
        .containsExactly("Ada Lovelace");
  }

  @Test
  void GivenAnEmptyStore_WhenListing_ThenTheListIsEmptyRatherThanNull() {
    assertThat(repository.findByOrganisation(ACME)).isNotNull().isEmpty();
  }

  @Test
  void GivenSomebodyWithNoContract_WhenReadBack_ThenTheyStillHaveNone() {
    Employee casual = employee(ACME, "Ada");
    repository.save(casual);

    assertThat(repository.findById(ACME, casual.id()))
        .get()
        .extracting(Employee::contractTerms)
        .satisfies(terms -> assertThat(terms).isEqualTo(Optional.empty()));
  }

  @Test
  void GivenContractedHours_WhenReadBack_ThenTheyComeBackExactly() {
    // Invented: contracted hours are a term of an individual agreement, never a statute's.
    Employee ada = employee(ACME, "Ada").contractedTo(new ContractTerms(Duration.ofMinutes(1261)));
    repository.save(ada);

    assertThat(repository.findById(ACME, ada.id()))
        .get()
        .extracting(employee -> employee.contractTerms().orElseThrow().weeklyContractedHours())
        .isEqualTo(Duration.ofMinutes(1261));
  }

  @Test
  void GivenQualificationsTheyHold_WhenReadBack_ThenAllOfThemComeBack() {
    QualificationId forklift = aQualification(ACME, "Forklift");
    QualificationId crane = aQualification(ACME, "Crane");
    Employee ada = employee(ACME, "Ada").holding(forklift).holding(crane);
    repository.save(ada);

    assertThat(repository.findById(ACME, ada.id()))
        .get()
        .extracting(Employee::qualifications)
        .isEqualTo(Set.of(forklift, crane));
  }

  @Test
  void GivenUnavailability_WhenReadBack_ThenEveryPeriodComesBack() {
    Employee ada = employee(ACME, "Ada").unavailableFor(A_FORTNIGHT_OFF).unavailableFor(A_WEEK_OFF);
    repository.save(ada);

    assertThat(repository.findById(ACME, ada.id()))
        .get()
        .extracting(Employee::unavailability)
        .isEqualTo(List.of(A_FORTNIGHT_OFF, A_WEEK_OFF));
  }

  @Test
  void GivenPreferences_WhenReadBack_ThenEachKeepsItsStrength() {
    ShiftPreference wants = new ShiftPreference(MONDAY_DAY, PreferenceStrength.PREFERS);
    ShiftPreference wouldRather = new ShiftPreference(A_WEEK_OFF, PreferenceStrength.PREFERS_NOT);
    Employee ada = employee(ACME, "Ada").preferring(wants).preferring(wouldRather);
    repository.save(ada);

    assertThat(repository.findById(ACME, ada.id()))
        .get()
        .extracting(Employee::preferences)
        .isEqualTo(List.of(wants, wouldRather));
  }

  /**
   * The save that matters most: an employee is four tables, and re-saving has to leave the other
   * three agreeing with the first rather than accumulating what was there before.
   */
  @Test
  void GivenAnEmployeeWhoseUnavailabilityWasStored_WhenTheyAreSavedAgain_ThenNothingIsDuplicated() {
    Employee ada = employee(ACME, "Ada").unavailableFor(A_FORTNIGHT_OFF);
    repository.save(ada);

    repository.save(ada.unavailableFor(A_WEEK_OFF));

    assertThat(repository.findById(ACME, ada.id()))
        .get()
        .extracting(Employee::unavailability)
        .isEqualTo(List.of(A_FORTNIGHT_OFF, A_WEEK_OFF));
  }

  /**
   * The other direction, which a withdrawal (F26, ADR-040) is the first path to take: a save with
   * fewer owned rows than were stored removes exactly those, and leaves the other three tables as
   * they were. Twenty contracted hours is invented.
   */
  @Test
  void GivenAStoredEmployee_WhenSavedWithOneDeclarationWithdrawn_ThenOnlyThatOneIsGone() {
    QualificationId forklift = aQualification(ACME, "Forklift");
    ShiftPreference wants = new ShiftPreference(MONDAY_DAY, PreferenceStrength.PREFERS);
    Employee ada =
        employee(ACME, "Ada")
            .holding(forklift)
            // Invented: contracted hours are a term of an individual agreement.
            .contractedTo(new ContractTerms(Duration.ofMinutes(1201)))
            .unavailableFor(A_FORTNIGHT_OFF)
            .unavailableFor(A_WEEK_OFF)
            .preferring(wants);
    repository.save(ada);

    repository.save(ada.noLongerUnavailableFor(A_FORTNIGHT_OFF));

    Employee stored = repository.findById(ACME, ada.id()).orElseThrow();
    assertThat(stored.unavailability()).containsExactly(A_WEEK_OFF);
    assertThat(stored.preferences()).containsExactly(wants);
    assertThat(stored.qualifications()).containsExactly(forklift);
    assertThat(stored.contractTerms())
        .map(ContractTerms::weeklyContractedHours)
        .contains(Duration.ofMinutes(1201));
  }

  /**
   * {@code save} is the one repository method carrying no {@code OrganisationId}, so the upsert is
   * the only thing standing between a re-used identity and another tenant's row. A UUID collision
   * is not a realistic accident; a restore, an import or a caller supplying its own identity is,
   * and a cross-tenant write is a security incident rather than a bug.
   */
  @Test
  void GivenAnIdentityHeldByAnotherOrganisation_WhenSavingOverIt_ThenItIsRefused() {
    Employee theirs = employee(RIVAL, "Their Employee");
    repository.save(theirs);

    assertThatThrownBy(() -> repository.save(new Employee(theirs.id(), ACME, "Ada")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another organisation");
  }

  @Test
  void GivenAnIdentityHeldByAnotherOrganisation_WhenSavingOverIt_ThenTheirRowIsUntouched() {
    Employee theirs = employee(RIVAL, "Their Employee");
    repository.save(theirs);

    assertThatThrownBy(() -> repository.save(new Employee(theirs.id(), ACME, "Ada")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(repository.findById(RIVAL, theirs.id()))
        .get()
        .extracting(Employee::name)
        .isEqualTo("Their Employee");
  }

  /**
   * The schema does not refuse overlapping rows, so only the rebuild through the aggregate's own
   * withers stops a row written by hand, a restore or a bug from handing the domain a state R7
   * forbids. Loading such an employee fails loudly, by one identity and in a listing alike.
   *
   * <p>Loudly means as our defect, not as the domain's refusal: that refusal is how a caller is
   * told their own request overlaps, and is answered 400 without a log line, so a store breaking R7
   * would read as the caller's mistake and leave the operator nothing. It carries no cause either,
   * because Spring resolves a handler by an exception's causes too, and would find that one's. The
   * message names the identity and the rule only, never the name or a span.
   */
  @Test
  void GivenOverlappingUnavailabilityInTheStore_WhenLoaded_ThenItFailsAsADefect() {
    Employee ada = employee(ACME, "Ada");
    repository.save(ada);
    // Invented: a week that overlaps the last day of A_FORTNIGHT_OFF.
    TimeRange overlapping =
        new TimeRange(Instant.parse("2026-08-14T00:00:00Z"), Instant.parse("2026-08-21T00:00:00Z"));
    storeUnavailabilityDirectly(ada, 0, A_FORTNIGHT_OFF);
    storeUnavailabilityDirectly(ada, 1, overlapping);

    assertThatThrownBy(() -> repository.findById(ACME, ada.id()))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause()
        .hasMessageContaining(ada.id().value().toString())
        .hasMessageContaining("R7")
        .message()
        .doesNotContain(ada.name());
    assertThatThrownBy(() -> repository.findByOrganisation(ACME))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause()
        .hasMessageContaining(ada.id().value().toString())
        .hasMessageContaining("R7")
        .message()
        .doesNotContain(ada.name());
  }

  /** Writes past the adapter, because no {@code Employee} can carry what this stores. */
  private static void storeUnavailabilityDirectly(
      Employee employee, int ordinal, TimeRange period) {
    PostgresDatabase.jdbcClient()
        .sql(
            """
            INSERT INTO employee_unavailability (employee_id, ordinal, starts_at, ends_at)
            VALUES (:id, :ordinal, :startsAt, :endsAt)
            """)
        .param("id", employee.id().value())
        .param("ordinal", ordinal)
        .param("startsAt", period.start().atOffset(ZoneOffset.UTC))
        .param("endsAt", period.end().atOffset(ZoneOffset.UTC))
        .update();
  }

  @Test
  void GivenTwoEmployeesWithOwnedRows_WhenListed_ThenNeitherGetsTheOthers() {
    Employee ada = employee(ACME, "Ada").unavailableFor(A_FORTNIGHT_OFF);
    Employee grace = employee(ACME, "Grace").unavailableFor(A_WEEK_OFF);
    repository.save(ada);
    repository.save(grace);

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Employee::unavailability)
        .containsExactly(List.of(A_FORTNIGHT_OFF), List.of(A_WEEK_OFF));
  }

  /**
   * #33's lost update, for an employee. Each change reads the employee, adds to them and stores
   * them whole, so without a lock the later save deletes what the earlier one added - here, an
   * absence declared by one request while another declares a different one. Read for update, the
   * second change waits for the first to commit and then reads it, and both survive.
   *
   * <p>The first change stores only once the second is waiting on its row. If the second does not
   * wait at all, the first goes on as soon as the second has read, and the lost absence is what
   * fails the test.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void GivenAChangeHoldingAnEmployee_WhenAnotherReadsThemForUpdate_ThenItWaitsAndBothAreKept()
      throws Exception {
    Employee ada = employee(ACME, "Ada");
    repository.save(ada);

    AtomicInteger second = new AtomicInteger();
    AtomicBoolean secondHasRead = new AtomicBoolean();
    List<Throwable> refusals =
        refusalsOf(
            waitForTheOther -> {
              Employee held = repository.findForUpdate(ACME, ada.id()).orElseThrow();
              waitForTheOther.run();
              awaitWaitingOnALockOr(second.get(), secondHasRead::get);
              repository.save(held.unavailableFor(A_FORTNIGHT_OFF));
            },
            waitForTheOther -> {
              second.set(backendServingThisTransaction());
              waitForTheOther.run();
              Employee read = repository.findForUpdate(ACME, ada.id()).orElseThrow();
              secondHasRead.set(true);
              repository.save(read.unavailableFor(A_WEEK_OFF));
            });

    assertThat(refusals).isEmpty();
    assertThat(repository.findById(ACME, ada.id()).orElseThrow().unavailability())
        .containsExactlyInAnyOrder(A_FORTNIGHT_OFF, A_WEEK_OFF);
  }

  /**
   * In autocommit the row would be released the moment the statement reading it ended, so the
   * caller would believe it held an employee nobody was waiting on.
   */
  @Test
  void GivenNoTransaction_WhenReadingAnEmployeeForUpdate_ThenItIsRefused() {
    Employee ada = employee(ACME, "Ada");
    repository.save(ada);

    assertThatThrownBy(() -> repository.findForUpdate(ACME, ada.id()))
        .isInstanceOf(IllegalStateException.class);
  }

  /** A use case named {@code find…} runs read-only, where PostgreSQL refuses to hold a row. */
  @Test
  void GivenAReadOnlyTransaction_WhenReadingAnEmployeeForUpdate_ThenItIsRefused() {
    Employee ada = employee(ACME, "Ada");
    repository.save(ada);
    TransactionTemplate reading = new TransactionTemplate(PostgresDatabase.transactions());
    reading.setReadOnly(true);

    assertThatThrownBy(
            () -> reading.executeWithoutResult(status -> repository.findForUpdate(ACME, ada.id())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenReadForUpdateByUs_ThenTheyAreNotFound() {
    Employee theirs = employee(RIVAL, "Their Employee");
    repository.save(theirs);

    Optional<Employee> found =
        new TransactionTemplate(PostgresDatabase.transactions())
            .execute(status -> repository.findForUpdate(ACME, theirs.id()));

    assertThat(found).isEmpty();
  }
}
