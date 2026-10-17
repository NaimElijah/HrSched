package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tenant predicate, the overlap semantics, and the ordering this adapter promises.
 *
 * <p>The organisation filter is the reason these tests exist: only it stops one organisation
 * reading another's shifts. It is now a predicate in the query rather than a filter after a map
 * lookup, and these are the tests the replacement had to keep satisfying.
 *
 * <p>{@code findWithin} asks for overlap, half-open at both ends. The three boundary cases below
 * are what pin that, because they are the ones a hand-written {@code WHERE} clause gets wrong.
 */
class JdbcShiftRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();

  private final JdbcShiftRepository repository =
      new JdbcShiftRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
    PostgresDatabase.given(ACME, RIVAL);
  }

  private static TimeRange between(String start, String end) {
    return new TimeRange(Instant.parse(start), Instant.parse(end));
  }

  private static Shift shift(OrganisationId organisation, String start, String end) {
    return new Shift(
        ShiftId.newId(), organisation, between(start, end), ZoneOffset.UTC, 1, Set.of());
  }

  private static TimeRange theDay() {
    return between("2026-09-07T00:00:00Z", "2026-09-08T00:00:00Z");
  }

  /** Ours, under their identity, and differing in every column the upsert would overwrite. */
  private static Shift overwriting(Shift theirs) {
    return new Shift(
        theirs.id(),
        ACME,
        between("2026-09-07T09:00:00Z", "2026-09-07T17:00:00Z"),
        ZoneId.of("Europe/London"),
        2,
        Set.of());
  }

  @Test
  void GivenAShift_WhenFindingItInItsOwnOrganisation_ThenItIsFound() {
    Shift morning = shift(ACME, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    repository.save(morning);

    assertThat(repository.findById(ACME, morning.id())).contains(morning);
  }

  /**
   * Shifts compare by identity, so finding one says nothing about its zone; the zone its days are
   * counted in (R36) is asserted on its own, through both reads.
   */
  @Test
  void GivenAShiftInAZoneOtherThanUtc_WhenReadBack_ThenItKeepsItsZone() {
    ZoneId london = ZoneId.of("Europe/London");
    Shift evening =
        new Shift(
            ShiftId.newId(),
            ACME,
            between("2026-09-07T17:00:00Z", "2026-09-07T23:00:00Z"),
            london,
            1,
            Set.of());
    repository.save(evening);

    assertThat(repository.findById(ACME, evening.id()))
        .get()
        .extracting(Shift::zone)
        .isEqualTo(london);
    assertThat(repository.findWithin(ACME, theDay()))
        .singleElement()
        .extracting(Shift::zone)
        .isEqualTo(london);
  }

  @Test
  void GivenAShiftOfAnotherOrganisation_WhenFindingItById_ThenItIsNotFound() {
    Shift theirs = shift(RIVAL, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    repository.save(theirs);

    assertThat(repository.findById(ACME, theirs.id())).isEmpty();
  }

  @Test
  void GivenNoSuchShift_WhenFindingItById_ThenItIsNotFound() {
    assertThat(repository.findById(ACME, ShiftId.newId())).isEmpty();
  }

  @Test
  void GivenShiftsInTwoOrganisations_WhenFindingWithinASpan_ThenOnlyOurOwnAreReturned() {
    Shift ours = shift(ACME, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    repository.save(ours);
    repository.save(shift(RIVAL, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z"));

    assertThat(repository.findWithin(ACME, theDay())).containsExactly(ours);
  }

  @Test
  void GivenAShiftOutsideTheSpan_WhenFindingWithin_ThenItIsNotReturned() {
    repository.save(shift(ACME, "2026-09-10T08:00:00Z", "2026-09-10T16:00:00Z"));

    assertThat(repository.findWithin(ACME, theDay())).isEmpty();
  }

  @Test
  void GivenAShiftStraddlingTheSpanEdge_WhenFindingWithin_ThenItIsReturned() {
    Shift overnight = shift(ACME, "2026-09-07T22:00:00Z", "2026-09-08T06:00:00Z");
    repository.save(overnight);

    assertThat(repository.findWithin(ACME, theDay())).containsExactly(overnight);
  }

  @Test
  void GivenAShiftEndingExactlyWhenTheSpanBegins_WhenFindingWithin_ThenItIsNotReturned() {
    repository.save(shift(ACME, "2026-09-06T16:00:00Z", "2026-09-07T00:00:00Z"));

    assertThat(repository.findWithin(ACME, theDay())).isEmpty();
  }

  @Test
  void GivenAShiftStartingExactlyWhenTheSpanEnds_WhenFindingWithin_ThenItIsNotReturned() {
    repository.save(shift(ACME, "2026-09-08T00:00:00Z", "2026-09-08T08:00:00Z"));

    assertThat(repository.findWithin(ACME, theDay())).isEmpty();
  }

  @Test
  void GivenSeveralShifts_WhenFindingWithin_ThenTheyComeBackInStartOrder() {
    Shift evening = shift(ACME, "2026-09-07T16:00:00Z", "2026-09-07T23:00:00Z");
    Shift morning = shift(ACME, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    repository.save(evening);
    repository.save(morning);

    assertThat(repository.findWithin(ACME, theDay())).containsExactly(morning, evening);
  }

  @Test
  void GivenTheSameShiftSavedTwice_WhenFindingIt_ThenOnlyTheLatestIsHeld() {
    Shift original = shift(ACME, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    Shift revised =
        new Shift(
            original.id(),
            ACME,
            between("2026-09-07T09:00:00Z", "2026-09-07T17:00:00Z"),
            ZoneOffset.UTC,
            2,
            Set.of());
    repository.save(original);
    repository.save(revised);

    // Shift compares by identity, so asserting on the element itself would pass whichever
    // version were held. A distinguishing field is the only thing that can tell them apart.
    assertThat(repository.findWithin(ACME, theDay()))
        .singleElement()
        .extracting(Shift::requiredHeadcount)
        .isEqualTo(2);
  }

  @Test
  void GivenNothingSaved_WhenFindingWithinASpan_ThenNothingIsReturned() {
    assertThat(repository.findWithin(ACME, theDay())).isEmpty();
  }

  @Test
  void GivenRequiredQualifications_WhenReadBack_ThenAllOfThemComeBack() {
    QualificationId forklift = QualificationId.newId();
    QualificationId crane = QualificationId.newId();
    Shift morning =
        new Shift(
            ShiftId.newId(),
            ACME,
            between("2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z"),
            ZoneOffset.UTC,
            1,
            Set.of(forklift, crane));
    repository.save(morning);

    assertThat(repository.findById(ACME, morning.id()))
        .get()
        .extracting(Shift::requiredQualifications)
        .isEqualTo(Set.of(forklift, crane));
  }

  /** Re-saving must leave the child table agreeing with the shift, not accumulating rows. */
  @Test
  void GivenAShiftWhoseRequirementsChange_WhenSavedAgain_ThenTheOldOnesAreGone() {
    QualificationId forklift = QualificationId.newId();
    QualificationId crane = QualificationId.newId();
    ShiftId id = ShiftId.newId();
    TimeRange period = between("2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    repository.save(new Shift(id, ACME, period, ZoneOffset.UTC, 1, Set.of(forklift)));

    repository.save(new Shift(id, ACME, period, ZoneOffset.UTC, 1, Set.of(crane)));

    assertThat(repository.findById(ACME, id))
        .get()
        .extracting(Shift::requiredQualifications)
        .isEqualTo(Set.of(crane));
  }

  /**
   * {@code save} is the one repository method carrying no {@code OrganisationId}, so the upsert is
   * the only thing standing between a re-used identity and another tenant's row. A cross-tenant
   * write is a security incident rather than a bug.
   */
  @Test
  void GivenAnIdentityHeldByAnotherOrganisation_WhenSavingOverIt_ThenItIsRefused() {
    Shift theirs = shift(RIVAL, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    repository.save(theirs);

    assertThatThrownBy(() -> repository.save(overwriting(theirs)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another organisation");
  }

  @Test
  void GivenAnIdentityHeldByAnotherOrganisation_WhenSavingOverIt_ThenTheirRowIsUntouched() {
    Shift theirs = shift(RIVAL, "2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z");
    repository.save(theirs);

    assertThatThrownBy(() -> repository.save(overwriting(theirs)))
        .isInstanceOf(IllegalStateException.class);

    // Shift compares by identity, so only a field the overwrite would change can show it did not.
    assertThat(repository.findById(RIVAL, theirs.id()))
        .get()
        .extracting(Shift::period)
        .isEqualTo(theirs.period());
  }
}
