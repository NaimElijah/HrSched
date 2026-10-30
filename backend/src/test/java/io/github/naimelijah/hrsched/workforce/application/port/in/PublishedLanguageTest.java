package io.github.naimelijah.hrsched.workforce.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

  private static RosterableEmployee ada() {
    return new RosterableEmployee(
        EmployeeId.newId(), "Ada", Set.of(), List.of(), Optional.empty(), List.of(), List.of());
  }

  private static Stream<ThrowingCallable> nullFields() {
    return Stream.of(
        () ->
            new RosterableEmployee(
                null, "Ada", Set.of(), List.of(), Optional.empty(), List.of(), List.of()),
        () ->
            new RosterableEmployee(
                EmployeeId.newId(),
                null,
                Set.of(),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of()),
        () ->
            new RosterableEmployee(
                EmployeeId.newId(), "Ada", null, List.of(), Optional.empty(), List.of(), List.of()),
        () ->
            new RosterableEmployee(
                EmployeeId.newId(), "Ada", Set.of(), null, Optional.empty(), List.of(), List.of()),
        () ->
            new RosterableEmployee(
                EmployeeId.newId(), "Ada", Set.of(), List.of(), null, List.of(), List.of()),
        () ->
            new RosterableEmployee(
                EmployeeId.newId(), "Ada", Set.of(), List.of(), Optional.empty(), null, List.of()),
        () ->
            new RosterableEmployee(
                EmployeeId.newId(), "Ada", Set.of(), List.of(), Optional.empty(), List.of(), null),
        () -> new PublishedQualification(null, "Forklift"),
        () -> new PublishedQualification(QualificationId.newId(), null));
  }

  @ParameterizedTest
  @MethodSource("nullFields")
  void GivenANullField_WhenPublishing_ThenItIsRejected(ThrowingCallable constructing) {
    assertThatThrownBy(constructing).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenNoContract_WhenPublished_ThenTheHoursAreAbsentRatherThanNull() {
    assertThat(ada().weeklyContractedHours()).isEmpty();
  }

  @Test
  void GivenAContract_WhenPublished_ThenTheHoursTravelWithTheEmployee() {
    RosterableEmployee contracted =
        new RosterableEmployee(
            EmployeeId.newId(),
            "Ada",
            Set.of(),
            List.of(),
            Optional.of(Duration.ofMinutes(2401)),
            List.of(),
            List.of()); // invented

    assertThat(contracted.weeklyContractedHours()).contains(Duration.ofMinutes(2401));
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
