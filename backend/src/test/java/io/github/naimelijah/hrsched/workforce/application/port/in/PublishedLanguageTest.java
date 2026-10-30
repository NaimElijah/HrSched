package io.github.naimelijah.hrsched.workforce.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What workforce tells another context, and nothing more.
 *
 * <p>These two records are the entire vocabulary another context may depend on, so their shape is a
 * contract rather than an implementation detail. A {@code PublishedQualification} is a record while
 * the {@code Qualification} entity behind it is not: the entity compares by identity because a
 * renamed qualification is the same qualification, whereas the published copy is a snapshot and
 * compares by everything it carries.
 *
 * <p>Every instant and figure below is invented. None is taken from any statute.
 */
class PublishedLanguageTest {

  private static final TimeRange MONDAY_DAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));
  private static final TimeRange TUESDAY_DAY =
      new TimeRange(Instant.parse("2026-09-08T08:00:00Z"), Instant.parse("2026-09-08T16:00:00Z"));

  private static RosterableEmployee ada(
      Set<QualificationId> qualifications, List<TimeRange> unavailability) {
    return new RosterableEmployee(
        EmployeeId.newId(), "Ada", qualifications, unavailability, List.of(), List.of());
  }

  private static Stream<ThrowingCallable> nullFields() {
    return Stream.of(
        () -> new RosterableEmployee(null, "Ada", Set.of(), List.of(), List.of(), List.of()),
        () ->
            new RosterableEmployee(
                EmployeeId.newId(), null, Set.of(), List.of(), List.of(), List.of()),
        () ->
            new RosterableEmployee(
                EmployeeId.newId(), "Ada", null, List.of(), List.of(), List.of()),
        () ->
            new RosterableEmployee(EmployeeId.newId(), "Ada", Set.of(), null, List.of(), List.of()),
        () ->
            new RosterableEmployee(EmployeeId.newId(), "Ada", Set.of(), List.of(), null, List.of()),
        () ->
            new RosterableEmployee(EmployeeId.newId(), "Ada", Set.of(), List.of(), List.of(), null),
        () -> new PublishedQualification(null, "Forklift"),
        () -> new PublishedQualification(QualificationId.newId(), null));
  }

  @ParameterizedTest
  @MethodSource("nullFields")
  void GivenANullField_WhenPublishing_ThenItIsRejected(ThrowingCallable constructing) {
    assertThatThrownBy(constructing).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenAnEmployeeHoldingEveryRequirement_WhenAskedIfTheyHoldThemAll_ThenTheyDo() {
    QualificationId forklift = QualificationId.newId();

    assertThat(ada(Set.of(forklift), List.of()).holdsAll(Set.of(forklift))).isTrue();
  }

  @Test
  void GivenAnEmployeeMissingOneRequirement_WhenAskedIfTheyHoldThemAll_ThenTheyDoNot() {
    assertThat(ada(Set.of(), List.of()).holdsAll(Set.of(QualificationId.newId()))).isFalse();
  }

  @Test
  void GivenNoRequirements_WhenAskedIfTheyHoldThemAll_ThenTheyDo() {
    assertThat(ada(Set.of(), List.of()).holdsAll(Set.of())).isTrue();
  }

  @Test
  void GivenAnEmployeeUnavailableForTheSpan_WhenAskedIfTheyAreAvailable_ThenTheyAreNot() {
    assertThat(ada(Set.of(), List.of(MONDAY_DAY)).isAvailableFor(MONDAY_DAY)).isFalse();
  }

  @Test
  void GivenAnEmployeeUnavailableElsewhere_WhenAskedIfTheyAreAvailable_ThenTheyAre() {
    assertThat(ada(Set.of(), List.of(TUESDAY_DAY)).isAvailableFor(MONDAY_DAY)).isTrue();
  }

  /**
   * The published record carries exactly what a roster generator needs, and in particular no
   * contracted hours: those are personal data no other context needs, kept to workforce's own
   * {@code EmployeeRecord}. Adding a component here widens what every consuming context is told,
   * which is a decision, so it fails this test first.
   */
  @Test
  void GivenThePublishedEmployee_WhenListingWhatItCarries_ThenItIsExactlyWhatAGeneratorNeeds() {
    assertThat(RosterableEmployee.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly(
            "id",
            "name",
            "qualifications",
            "unavailability",
            "wouldLikeToWork",
            "wouldRatherNotWork");
  }

  @Test
  void GivenTwoPublishedQualificationsSharingBothFields_WhenComparingThem_ThenTheyAreEqual() {
    QualificationId id = QualificationId.newId();

    assertThat(new PublishedQualification(id, "Forklift"))
        .isEqualTo(new PublishedQualification(id, "Forklift"));
  }

  /**
   * Unlike the entity it copies, the published form compares by everything it carries: it is a
   * snapshot of a name at a moment, not the thing whose name changed.
   */
  @Test
  void GivenTwoPublishedQualificationsSharingAnIdButNotAName_WhenComparingThem_ThenTheyDiffer() {
    QualificationId id = QualificationId.newId();

    assertThat(new PublishedQualification(id, "Forklift"))
        .isNotEqualTo(new PublishedQualification(id, "Forklift Licence"));
  }
}
