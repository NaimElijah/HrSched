package io.github.naimelijah.hrsched.scheduling.adapter.out.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.domain.model.StatedPreferences;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.workforce.application.port.in.DefineQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.FindRosterableEmployeesUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageQualificationsUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.PublishedQualification;
import io.github.naimelijah.hrsched.workforce.application.port.in.RosterableEmployee;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Specification of the anti-corruption layer between scheduling and workforce.
 *
 * <p>This is the one class in scheduling permitted to name another context, so it is the one place
 * where the two models can quietly fuse. Its whole job is translation and narrowing: scheduling
 * asks its own question through its own port and receives its own type back.
 *
 * <p>The narrowing is worth asserting rather than assuming. A {@code RosterableEmployee} carries
 * contracted hours; a {@code RosterCandidate} deliberately does not, because the generator has no
 * business knowing them. If that field were ever added, generation would start depending on data
 * workforce is free to change.
 */
class WorkforceDirectoryAdapterTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final TimeRange MONDAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));

  private final FakeWorkforce workforce = new FakeWorkforce();
  private final FakeQualifications qualifications = new FakeQualifications();
  private final WorkforceDirectoryAdapter adapter =
      new WorkforceDirectoryAdapter(workforce, qualifications);

  private static TenantActor scheduler() {
    return new TenantActor(UserId.newId(), ACME, Role.SCHEDULER);
  }

  @Test
  void GivenARosterableEmployee_WhenTranslated_ThenSchedulingReceivesItsOwnType() {
    EmployeeId ada = EmployeeId.newId();
    workforce.publish(
        new RosterableEmployee(
            ada, "Ada", Set.of(), List.of(), Optional.empty(), List.of(), List.of()));

    assertThat(adapter.candidatesFor(scheduler(), ACME))
        .containsExactly(
            new RosterCandidate(ada, "Ada", Set.of(), List.of(), StatedPreferences.none()));
  }

  @Test
  void GivenQualificationsAndUnavailability_WhenTranslated_ThenBothSurviveIntact() {
    EmployeeId ada = EmployeeId.newId();
    QualificationId forklift = QualificationId.newId();
    workforce.publish(
        new RosterableEmployee(
            ada, "Ada", Set.of(forklift), List.of(MONDAY), Optional.empty(), List.of(), List.of()));

    RosterCandidate candidate = adapter.candidatesFor(scheduler(), ACME).getFirst();

    assertThat(candidate.qualifications()).containsExactly(forklift);
    assertThat(candidate.unavailability()).containsExactly(MONDAY);
  }

  /**
   * The narrowing itself: hours are workforce's business and must not cross into scheduling. Two
   * employees identical but for their contract translate to the same candidate, which is only true
   * while the candidate has nowhere to put the difference.
   *
   * <p>The figure is invented; contracted hours are a term of an agreement, not a statute.
   */
  @Test
  void GivenTwoEmployeesDifferingOnlyInContract_WhenTranslated_ThenTheCandidatesAreIdentical() {
    EmployeeId ada = EmployeeId.newId();
    workforce.publish(
        new RosterableEmployee(
            ada,
            "Ada",
            Set.of(),
            List.of(),
            Optional.of(Duration.ofMinutes(2400)),
            List.of(),
            List.of()));
    RosterCandidate withContract = adapter.candidatesFor(scheduler(), ACME).getFirst();

    FakeWorkforce withoutContract = new FakeWorkforce();
    withoutContract.publish(
        new RosterableEmployee(
            ada, "Ada", Set.of(), List.of(), Optional.empty(), List.of(), List.of()));
    RosterCandidate uncontracted =
        new WorkforceDirectoryAdapter(withoutContract, qualifications)
            .candidatesFor(scheduler(), ACME)
            .getFirst();

    assertThat(withContract).isEqualTo(uncontracted);
  }

  /**
   * Preferences do cross, unlike hours: they are what the preference objective weighs (ADR-010).
   * Each strength arrives as its own list, so scheduling never names workforce's enum for it.
   */
  @Test
  void GivenStatedPreferences_WhenTranslated_ThenEachStrengthArrivesAsItsOwnSpans() {
    EmployeeId ada = EmployeeId.newId();
    TimeRange tuesday =
        new TimeRange(Instant.parse("2026-09-08T08:00:00Z"), Instant.parse("2026-09-08T16:00:00Z"));
    workforce.publish(
        new RosterableEmployee(
            ada, "Ada", Set.of(), List.of(), Optional.empty(), List.of(MONDAY), List.of(tuesday)));

    RosterCandidate candidate = adapter.candidatesFor(scheduler(), ACME).getFirst();

    assertThat(candidate.preferences())
        .isEqualTo(new StatedPreferences(List.of(MONDAY), List.of(tuesday)));
  }

  /** The caller's own identity is what workforce must authorise against, never a substitute. */
  @Test
  void GivenTheActorAndOrganisation_WhenAsking_ThenTheyArePassedThroughUnchanged() {
    TenantActor caller = scheduler();

    adapter.candidatesFor(caller, ACME);

    assertThat(workforce.lastActor).isSameAs(caller);
    assertThat(workforce.lastOrganisation).isEqualTo(ACME);
  }

  @Test
  void GivenPublishedQualifications_WhenAskingWhichAreDefined_ThenOnlyIdentitiesComeBack() {
    QualificationId forklift = QualificationId.newId();
    QualificationId welding = QualificationId.newId();
    qualifications.publish(new PublishedQualification(forklift, "Forklift"));
    qualifications.publish(new PublishedQualification(welding, "Welding"));

    assertThat(adapter.definedQualifications(scheduler(), ACME))
        .containsExactlyInAnyOrder(forklift, welding);
  }

  @Test
  void GivenNoEmployees_WhenAskingForCandidates_ThenTheListIsEmptyRatherThanNull() {
    assertThat(adapter.candidatesFor(scheduler(), ACME)).isNotNull().isEmpty();
  }

  @Test
  void GivenNoQualifications_WhenAskingWhichAreDefined_ThenTheSetIsEmptyRatherThanNull() {
    assertThat(adapter.definedQualifications(scheduler(), ACME)).isNotNull().isEmpty();
  }

  /**
   * A swallowed refusal here would be worse than a failure: generation would proceed against an
   * empty workforce and produce a roster that looks merely understaffed.
   */
  @Test
  void GivenWorkforceRefusesTheCaller_WhenAskingForCandidates_ThenTheRefusalPropagates() {
    workforce.refuse();

    assertThatThrownBy(() -> adapter.candidatesFor(scheduler(), ACME))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenWorkforceRefusesTheCaller_WhenAskingWhichAreDefined_ThenTheRefusalPropagates() {
    qualifications.refuse();

    assertThatThrownBy(() -> adapter.definedQualifications(scheduler(), ACME))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  private static final class FakeWorkforce implements FindRosterableEmployeesUseCase {

    private final List<RosterableEmployee> published = new ArrayList<>();
    private boolean refusing;
    private Actor lastActor;
    private OrganisationId lastOrganisation;

    void publish(RosterableEmployee employee) {
      published.add(employee);
    }

    void refuse() {
      refusing = true;
    }

    @Override
    public List<RosterableEmployee> findRosterable(Actor actor, OrganisationId organisation) {
      lastActor = actor;
      lastOrganisation = organisation;
      if (refusing) {
        throw new CrossTenantAccessException(UserId.newId(), organisation);
      }
      return List.copyOf(published);
    }
  }

  private static final class FakeQualifications implements ManageQualificationsUseCase {

    private final List<PublishedQualification> published = new ArrayList<>();
    private boolean refusing;

    void publish(PublishedQualification qualification) {
      published.add(qualification);
    }

    void refuse() {
      refusing = true;
    }

    @Override
    public QualificationId defineQualification(Actor actor, DefineQualificationCommand command) {
      throw new UnsupportedOperationException("the adapter never defines qualifications");
    }

    @Override
    public Optional<PublishedQualification> findQualification(
        Actor actor, OrganisationId organisation, QualificationId qualification) {
      throw new UnsupportedOperationException("the adapter never reads one qualification");
    }

    @Override
    public List<PublishedQualification> findQualifications(
        Actor actor, OrganisationId organisation) {
      if (refusing) {
        throw new CrossTenantAccessException(UserId.newId(), organisation);
      }
      return List.copyOf(published);
    }
  }
}
