package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import java.lang.reflect.RecordComponent;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The two small records scheduling passes around: one assignment, and one broken rule.
 *
 * <p>Both are values rather than entities. Two assignments of the same person to the same shift are
 * the same fact rather than two facts, which is what makes a duplicate detectable at all.
 */
class SchedulingValueObjectsTest {

  private static Stream<ThrowingCallable> nullFields() {
    return Stream.of(
        () -> new Assignment(null, EmployeeId.newId()),
        () -> new Assignment(ShiftId.newId(), null),
        () -> new ConstraintViolation(null, "a rule", "a detail"),
        () -> new ConstraintViolation(EmployeeId.newId(), null, "a detail"),
        () -> new ConstraintViolation(EmployeeId.newId(), "a rule", null));
  }

  @ParameterizedTest
  @MethodSource("nullFields")
  void GivenANullField_WhenConstructingTheValue_ThenItIsRejected(ThrowingCallable constructing) {
    assertThatThrownBy(constructing).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenTwoAssignmentsOfTheSamePersonToTheSameShift_WhenComparingThem_ThenTheyAreEqual() {
    ShiftId shift = ShiftId.newId();
    EmployeeId ada = EmployeeId.newId();

    assertThat(new Assignment(shift, ada))
        .isEqualTo(new Assignment(shift, ada))
        .hasSameHashCodeAs(new Assignment(shift, ada));
  }

  @Test
  void GivenTheSameShiftAssignedToDifferentPeople_WhenComparingThem_ThenTheyDiffer() {
    ShiftId shift = ShiftId.newId();

    assertThat(new Assignment(shift, EmployeeId.newId()))
        .isNotEqualTo(new Assignment(shift, EmployeeId.newId()));
  }

  @Test
  void GivenTwoIdenticalViolations_WhenComparingThem_ThenTheyAreEqual() {
    EmployeeId ada = EmployeeId.newId();

    assertThat(new ConstraintViolation(ada, "maximum daily working time", "worked PT12H"))
        .isEqualTo(new ConstraintViolation(ada, "maximum daily working time", "worked PT12H"));
  }

  /**
   * The structured-rather-than-prose guarantee the user interface depends on. A caller has to
   * resolve the employee to a name and group by person, and doing that from a formatted sentence
   * means parsing back apart a string this service happens to format a particular way today.
   */
  @Test
  void GivenAViolation_WhenExamined_ThenItIsThreeFieldsRatherThanASentence() {
    assertThat(ConstraintViolation.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactlyInAnyOrder("employee", "constraint", "detail");
  }

  /** A date as ISO-8601 writes one; never found inside an identifier, whose groups are wider. */
  private static final String AN_ISO_DATE = "\\d{4}-\\d{2}-\\d{2}";

  /**
   * The span somebody declared they cannot work says something about their health or household, and
   * a refusal travels to whoever was refused. It names the shift, and nothing of the span.
   */
  @Test
  void GivenAnUnavailabilityBreach_WhenDescribed_ThenItNamesTheShiftAndNoSpan() {
    EmployeeId ada = EmployeeId.newId();
    ShiftId shift = ShiftId.newId();

    ConstraintViolation breach = ConstraintViolation.declaredUnavailable(ada, shift);

    assertThat(breach.employee()).isEqualTo(ada);
    assertThat(breach.constraint()).isEqualTo(ConstraintViolation.DECLARED_UNAVAILABLE);
    assertThat(breach.detail())
        .contains(shift.value().toString())
        .describedAs("no date, so no span")
        .doesNotContainPattern(AN_ISO_DATE);
  }

  @Test
  void GivenNoQualificationMissing_WhenDescribingWhatIsMissing_ThenItIsRejected() {
    assertThatThrownBy(
            () ->
                ConstraintViolation.missingQualifications(
                    EmployeeId.newId(), ShiftId.newId(), Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
