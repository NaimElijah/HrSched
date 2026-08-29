package io.github.naimelijah.hrsched.workforce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Specification of the employee aggregate. */
class EmployeeTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final TimeRange MONDAY_DAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));
  private static final TimeRange MONDAY_EVENING =
      new TimeRange(Instant.parse("2026-09-07T18:00:00Z"), Instant.parse("2026-09-07T23:00:00Z"));

  private static Employee ada() {
    return new Employee(EmployeeId.newId(), ACME, "Ada Lovelace");
  }

  @Test
  void GivenANonBlankName_WhenConstructingAnEmployee_ThenItIsAccepted() {
    assertThat(ada().name()).isEqualTo("Ada Lovelace");
  }

  @Test
  void GivenAWhitespaceOnlyName_WhenConstructingAnEmployee_ThenItIsRejected() {
    assertThatThrownBy(() -> new Employee(EmployeeId.newId(), ACME, "   "))
        .isInstanceOf(InvalidEmployeeNameException.class)
        .hasMessageContaining("not blank");
  }

  @Test
  void GivenANullName_WhenConstructingAnEmployee_ThenItIsRejected() {
    assertThatThrownBy(() -> new Employee(EmployeeId.newId(), ACME, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenANullOrganisation_WhenConstructingAnEmployee_ThenItIsRejected() {
    assertThatThrownBy(() -> new Employee(EmployeeId.newId(), null, "Ada Lovelace"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenTwoEmployeesSharingAName_WhenComparingThem_ThenIdentityDecides() {
    EmployeeId id = EmployeeId.newId();

    assertThat(new Employee(id, ACME, "Ada Lovelace")).isEqualTo(new Employee(id, ACME, "Ada L."));
    assertThat(ada()).isNotEqualTo(ada());
  }

  @Test
  void GivenAnEmployee_WhenGrantedAQualification_ThenTheyHoldIt() {
    QualificationId supervisor = QualificationId.newId();

    Employee qualified = ada().holding(supervisor);

    assertThat(qualified.qualifications()).containsExactly(supervisor);
    assertThat(qualified.holdsAll(Set.of(supervisor))).isTrue();
  }

  @Test
  void GivenAnEmployeeMissingOneRequirement_WhenAskedIfTheyQualify_ThenTheyDoNot() {
    QualificationId held = QualificationId.newId();
    QualificationId missing = QualificationId.newId();

    assertThat(ada().holding(held).holdsAll(Set.of(held, missing))).isFalse();
  }

  @Test
  void GivenTheSameQualificationTwice_WhenGrantingIt_ThenItIsHeldOnce() {
    QualificationId supervisor = QualificationId.newId();

    assertThat(ada().holding(supervisor).holding(supervisor).qualifications()).hasSize(1);
  }

  @Test
  void GivenNoDeclaredUnavailability_WhenAskedAboutAnySpan_ThenTheyAreAvailable() {
    assertThat(ada().isAvailableFor(MONDAY_DAY)).isTrue();
  }

  @Test
  void GivenUnavailabilityCoveringASpan_WhenAskedAboutIt_ThenTheyAreUnavailable() {
    Employee away = ada().unavailableFor(MONDAY_DAY);

    assertThat(away.isAvailableFor(MONDAY_DAY)).isFalse();
    assertThat(away.isAvailableFor(MONDAY_EVENING)).isTrue();
  }

  @Test
  void GivenAlreadyDeclaredUnavailability_WhenDeclaringAnOverlappingSpan_ThenItIsRejected() {
    Employee away = ada().unavailableFor(MONDAY_DAY);
    TimeRange overlapping =
        new TimeRange(Instant.parse("2026-09-07T12:00:00Z"), Instant.parse("2026-09-07T20:00:00Z"));

    assertThatThrownBy(() -> away.unavailableFor(overlapping))
        .isInstanceOf(OverlappingUnavailabilityException.class);
  }

  @Test
  void GivenSeparateSpans_WhenDeclaringUnavailabilityForBoth_ThenBothAreHeld() {
    Employee away = ada().unavailableFor(MONDAY_DAY).unavailableFor(MONDAY_EVENING);

    assertThat(away.unavailability()).hasSize(2);
  }

  @Test
  void GivenAnEmployee_WhenStatingAPreference_ThenItIsHeldAndNeverBlocksAvailability() {
    Employee choosy =
        ada().preferring(new ShiftPreference(MONDAY_EVENING, PreferenceStrength.PREFERS_NOT));

    assertThat(choosy.preferences()).hasSize(1);
    assertThat(choosy.isAvailableFor(MONDAY_EVENING))
        .describedAs("a preference is soft and must never block an assignment")
        .isTrue();
  }

  @Test
  void GivenANewEmployee_WhenAskedForContractTerms_ThenThereAreNone() {
    assertThat(ada().contractTerms()).isEmpty();
  }

  @Test
  void GivenAnEmployee_WhenContracted_ThenTheTermsAreHeld() {
    Employee parttime = ada().contractedTo(new ContractTerms(Duration.ofHours(20)));

    assertThat(parttime.contractTerms())
        .map(ContractTerms::weeklyContractedHours)
        .contains(Duration.ofHours(20));
  }

  @Test
  void GivenNonPositiveHours_WhenContracting_ThenItIsRejected() {
    assertThatThrownBy(() -> new ContractTerms(Duration.ZERO))
        .isInstanceOf(InvalidContractTermsException.class);
    assertThatThrownBy(() -> new ContractTerms(Duration.ofHours(-1)))
        .isInstanceOf(InvalidContractTermsException.class);
  }

  @Test
  void GivenAnEmployee_WhenChanged_ThenTheOriginalIsUntouched() {
    Employee original = ada();

    original.holding(QualificationId.newId()).unavailableFor(MONDAY_DAY);

    assertThat(original.qualifications()).isEmpty();
    assertThat(original.unavailability()).isEmpty();
  }
}
