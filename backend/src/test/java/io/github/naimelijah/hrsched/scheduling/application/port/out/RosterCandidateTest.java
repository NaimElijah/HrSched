package io.github.naimelijah.hrsched.scheduling.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Scheduling's own view of a person, and the question it exists to answer.
 *
 * <p>{@code couldCover} is R4 -- an employee may only cover a shift whose qualifications they all
 * hold -- together with the availability half. Until now the requirement named this class and a
 * test of a different one.
 *
 * <p>"Could" is the right word: this says an assignment is not obviously impossible, not that it is
 * legal. Working-time rules are checked separately, against the schedule as a whole.
 */
class RosterCandidateTest {

  private static final TimeRange MONDAY_DAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));
  private static final TimeRange TUESDAY_DAY =
      new TimeRange(Instant.parse("2026-09-08T08:00:00Z"), Instant.parse("2026-09-08T16:00:00Z"));

  private static RosterCandidate candidate(
      Set<QualificationId> qualifications, List<TimeRange> unavailability) {
    return new RosterCandidate(EmployeeId.newId(), "Ada", qualifications, unavailability);
  }

  private static Stream<ThrowingCallable> nullFields() {
    return Stream.of(
        () -> new RosterCandidate(null, "Ada", Set.of(), List.of()),
        () -> new RosterCandidate(EmployeeId.newId(), null, Set.of(), List.of()),
        () -> new RosterCandidate(EmployeeId.newId(), "Ada", null, List.of()),
        () -> new RosterCandidate(EmployeeId.newId(), "Ada", Set.of(), null));
  }

  @ParameterizedTest
  @MethodSource("nullFields")
  void GivenANullField_WhenConstructingACandidate_ThenItIsRejected(ThrowingCallable constructing) {
    assertThatThrownBy(constructing).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenACandidateHoldingEveryRequirement_WhenAskedIfTheyCouldCover_ThenTheyCould() {
    QualificationId forklift = QualificationId.newId();

    assertThat(candidate(Set.of(forklift), List.of()).couldCover(MONDAY_DAY, Set.of(forklift)))
        .isTrue();
  }

  @Test
  void GivenACandidateMissingOneRequirement_WhenAskedIfTheyCouldCover_ThenTheyCouldNot() {
    QualificationId forklift = QualificationId.newId();
    QualificationId welding = QualificationId.newId();

    assertThat(
            candidate(Set.of(forklift), List.of())
                .couldCover(MONDAY_DAY, Set.of(forklift, welding)))
        .isFalse();
  }

  @Test
  void GivenAShiftRequiringNothing_WhenAskedIfTheyCouldCover_ThenAnyAvailableCandidateCould() {
    assertThat(candidate(Set.of(), List.of()).couldCover(MONDAY_DAY, Set.of())).isTrue();
  }

  @Test
  void GivenACandidateUnavailableForPartOfTheShift_WhenAsked_ThenTheyCouldNot() {
    TimeRange mondayMorning =
        new TimeRange(Instant.parse("2026-09-07T06:00:00Z"), Instant.parse("2026-09-07T10:00:00Z"));

    assertThat(candidate(Set.of(), List.of(mondayMorning)).couldCover(MONDAY_DAY, Set.of()))
        .isFalse();
  }

  @Test
  void GivenACandidateUnavailableOnAnotherDay_WhenAsked_ThenTheyCould() {
    assertThat(candidate(Set.of(), List.of(TUESDAY_DAY)).couldCover(MONDAY_DAY, Set.of())).isTrue();
  }

  /** The question a refusal needs answered: not whether they lack something, but what. */
  @Test
  void GivenAQualificationTheyLack_WhenAskedWhatTheyLack_ThenOnlyThatOneIsNamed() {
    QualificationId forklift = QualificationId.newId();
    QualificationId welding = QualificationId.newId();

    assertThat(candidate(Set.of(forklift), List.of()).lacking(Set.of(forklift, welding)))
        .containsExactly(welding);
  }

  @Test
  void GivenEveryQualification_WhenAskedWhatTheyLack_ThenNothingIs() {
    QualificationId forklift = QualificationId.newId();

    assertThat(candidate(Set.of(forklift), List.of()).lacking(Set.of(forklift))).isEmpty();
  }

  @Test
  void GivenAbsenceOverlappingTheShift_WhenAskedIfUnavailable_ThenTheyAre() {
    TimeRange mondayMorning =
        new TimeRange(Instant.parse("2026-09-07T06:00:00Z"), Instant.parse("2026-09-07T10:00:00Z"));

    assertThat(candidate(Set.of(), List.of(mondayMorning)).isUnavailableDuring(MONDAY_DAY))
        .isTrue();
  }

  /** A span that ends as the shift begins leaves them free for all of it. */
  @Test
  void GivenAbsenceThatOnlyTouchesTheShift_WhenAskedIfUnavailable_ThenTheyAreNot() {
    TimeRange untilTheShiftStarts =
        new TimeRange(Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-07T08:00:00Z"));

    assertThat(candidate(Set.of(), List.of(untilTheShiftStarts)).isUnavailableDuring(MONDAY_DAY))
        .isFalse();
  }

  @Test
  void GivenAMutableSetOfQualifications_WhenConstructing_ThenLaterChangesAreNotSeen() {
    Set<QualificationId> held = new HashSet<>(Set.of(QualificationId.newId()));
    RosterCandidate ada = candidate(held, List.of());

    held.clear();

    assertThat(ada.qualifications()).hasSize(1);
  }

  @Test
  void GivenAMutableListOfUnavailability_WhenConstructing_ThenLaterChangesAreNotSeen() {
    List<TimeRange> unavailable = new ArrayList<>(List.of(MONDAY_DAY));
    RosterCandidate ada = candidate(Set.of(), unavailable);

    unavailable.clear();

    assertThat(ada.unavailability()).hasSize(1);
  }
}
