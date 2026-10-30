package io.github.naimelijah.hrsched.workforce.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
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
 * What workforce's own screens are told about somebody: the published record, and their contracted
 * hours beside it.
 *
 * <p>Every figure below is invented; contracted hours are a term of an individual agreement, and
 * none is taken from any statute.
 */
class EmployeeRecordTest {

  private static final RosterableEmployee ADA =
      new RosterableEmployee(EmployeeId.newId(), "Ada", Set.of(), List.of(), List.of(), List.of());

  private static Stream<ThrowingCallable> nullFields() {
    return Stream.of(
        () -> new EmployeeRecord(null, Optional.empty()), () -> new EmployeeRecord(ADA, null));
  }

  @ParameterizedTest
  @MethodSource("nullFields")
  void GivenANullField_WhenRecording_ThenItIsRejected(ThrowingCallable constructing) {
    assertThatThrownBy(constructing).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenNoContract_WhenRecorded_ThenTheHoursAreAbsentRatherThanNull() {
    assertThat(new EmployeeRecord(ADA, Optional.empty()).weeklyContractedHours()).isEmpty();
  }

  @Test
  void GivenAContract_WhenRecorded_ThenTheHoursTravelWithTheEmployee() {
    EmployeeRecord contracted =
        new EmployeeRecord(ADA, Optional.of(Duration.ofMinutes(2401))); // invented

    assertThat(contracted.weeklyContractedHours()).contains(Duration.ofMinutes(2401));
  }
}
