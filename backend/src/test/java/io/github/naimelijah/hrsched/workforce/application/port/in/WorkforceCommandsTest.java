package io.github.naimelijah.hrsched.workforce.application.port.in;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the workforce driving ports accept, and where they stop short.
 *
 * <p>Split into two tables by command family rather than one of sixteen rows: a table long enough
 * to need scrolling stops reading as a sentence and starts reading as a spreadsheet.
 *
 * <p>A command checks completeness, never sensibleness. A blank name passes here and is refused by
 * {@code Employee} and {@code Qualification}, which is the correct place for it.
 */
class WorkforceCommandsTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final EmployeeId ADA = EmployeeId.newId();
  private static final TimeRange MONDAY_DAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));

  private static Stream<ThrowingCallable> nullFieldsOnEmployeeCommands() {
    return Stream.of(
        () -> new AddEmployeeCommand(null, "Ada"),
        () -> new AddEmployeeCommand(ACME, null),
        () -> new GrantQualificationCommand(null, ADA, QualificationId.newId()),
        () -> new GrantQualificationCommand(ACME, null, QualificationId.newId()),
        () -> new GrantQualificationCommand(ACME, ADA, null),
        () -> new SetContractTermsCommand(null, ADA, Duration.ofMinutes(2400)),
        () -> new SetContractTermsCommand(ACME, null, Duration.ofMinutes(2400)),
        () -> new SetContractTermsCommand(ACME, ADA, null));
  }

  private static Stream<ThrowingCallable> nullFieldsOnAvailabilityCommands() {
    return Stream.of(
        () -> new DeclareUnavailabilityCommand(null, ADA, MONDAY_DAY),
        () -> new DeclareUnavailabilityCommand(ACME, null, MONDAY_DAY),
        () -> new DeclareUnavailabilityCommand(ACME, ADA, null),
        () -> new StatePreferenceCommand(null, ADA, MONDAY_DAY, PreferenceStrength.PREFERS),
        () -> new StatePreferenceCommand(ACME, null, MONDAY_DAY, PreferenceStrength.PREFERS),
        () -> new StatePreferenceCommand(ACME, ADA, null, PreferenceStrength.PREFERS),
        () -> new StatePreferenceCommand(ACME, ADA, MONDAY_DAY, null),
        () -> new DefineQualificationCommand(null, "Forklift"),
        () -> new DefineQualificationCommand(ACME, null));
  }

  @ParameterizedTest
  @MethodSource("nullFieldsOnEmployeeCommands")
  void GivenANullField_WhenConstructingAnEmployeeCommand_ThenItIsRejected(
      ThrowingCallable constructing) {
    assertThatThrownBy(constructing).isInstanceOf(NullPointerException.class);
  }

  @ParameterizedTest
  @MethodSource("nullFieldsOnAvailabilityCommands")
  void GivenANullField_WhenConstructingAnAvailabilityCommand_ThenItIsRejected(
      ThrowingCallable constructing) {
    assertThatThrownBy(constructing).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenABlankName_WhenAddingAnEmployee_ThenTheCommandAcceptsItAndTheDomainRefusesIt() {
    assertThatCode(() -> new AddEmployeeCommand(ACME, "   ")).doesNotThrowAnyException();
  }

  @Test
  void GivenANonPositiveDuration_WhenSettingTerms_ThenTheCommandAcceptsItAndTheDomainRefusesIt() {
    assertThatCode(() -> new SetContractTermsCommand(ACME, ADA, Duration.ZERO))
        .doesNotThrowAnyException();
  }
}
