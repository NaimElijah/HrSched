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

  /**
   * One of the two spans is an absence already stored, and a span says when somebody cannot work; a
   * message travels further than the record it describes, as {@link UnknownDeclarationException}
   * says. Both spans fall on the invented day {@code 2026-09-07}, so the day itself is what no
   * rendering of either may carry.
   */
  @Test
  void GivenAnOverlappingSpan_WhenItIsRefused_ThenTheMessageRepeatsNeitherSpan() {
    Employee away = ada().unavailableFor(MONDAY_DAY);
    // Invented: overlaps MONDAY_DAY's afternoon.
    TimeRange overlapping =
        new TimeRange(Instant.parse("2026-09-07T12:00:00Z"), Instant.parse("2026-09-07T20:00:00Z"));

    assertThatThrownBy(() -> away.unavailableFor(overlapping))
        .isInstanceOf(OverlappingUnavailabilityException.class)
        .message()
        .doesNotContain(MONDAY_DAY.start().toString())
        .doesNotContain(MONDAY_DAY.end().toString())
        .doesNotContain(overlapping.start().toString())
        .doesNotContain(overlapping.end().toString())
        .doesNotContain("2026-09-07");
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

  // --- F26: withdrawing a declaration (ADR-040) ---------------------------------------------

  @Test
  void GivenDeclaredUnavailability_WhenWithdrawingOneSpan_ThenOnlyThatSpanIsGone() {
    Employee declared = ada().unavailableFor(MONDAY_DAY).unavailableFor(MONDAY_EVENING);

    Employee withdrawn = declared.noLongerUnavailableFor(MONDAY_DAY);

    assertThat(withdrawn.unavailability()).containsExactly(MONDAY_EVENING);
    assertThat(withdrawn.isAvailableFor(MONDAY_DAY)).isTrue();
  }

  /** A withdrawal names a declaration; it does not carve a piece out of one. */
  @Test
  void GivenASpanThatOnlyOverlapsWhatWasDeclared_WhenWithdrawingIt_ThenItIsRejected() {
    Employee declared = ada().unavailableFor(MONDAY_DAY);
    TimeRange mondayMorning =
        new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T12:00:00Z"));

    assertThatThrownBy(() -> declared.noLongerUnavailableFor(mondayMorning))
        .isInstanceOf(UnknownDeclarationException.class);
  }

  @Test
  void GivenAStatedPreference_WhenWithdrawingIt_ThenItIsGone() {
    ShiftPreference rather = new ShiftPreference(MONDAY_DAY, PreferenceStrength.PREFERS);
    Employee stated = ada().preferring(rather);

    assertThat(stated.noLongerPreferring(rather).preferences()).isEmpty();
  }

  /**
   * Identical statements cannot be told apart (R26 lets them repeat), so withdrawing one withdraws
   * them all; a statement about the same span with another strength is a different statement.
   */
  @Test
  void GivenAPreferenceStatedTwice_WhenWithdrawingIt_ThenEveryIdenticalStatementIsGone() {
    ShiftPreference rather = new ShiftPreference(MONDAY_DAY, PreferenceStrength.PREFERS);
    ShiftPreference ratherNot = new ShiftPreference(MONDAY_DAY, PreferenceStrength.PREFERS_NOT);
    Employee stated = ada().preferring(rather).preferring(ratherNot).preferring(rather);

    assertThat(stated.noLongerPreferring(rather).preferences()).containsExactly(ratherNot);
  }

  @Test
  void GivenAPreferenceOfAnotherStrength_WhenWithdrawingIt_ThenItIsRejected() {
    Employee stated = ada().preferring(new ShiftPreference(MONDAY_DAY, PreferenceStrength.PREFERS));

    assertThatThrownBy(
            () ->
                stated.noLongerPreferring(
                    new ShiftPreference(MONDAY_DAY, PreferenceStrength.PREFERS_NOT)))
        .isInstanceOf(UnknownDeclarationException.class);
  }

  @Test
  void GivenADeclaration_WhenWithdrawn_ThenTheOriginalIsUntouched() {
    ShiftPreference rather = new ShiftPreference(MONDAY_EVENING, PreferenceStrength.PREFERS);
    Employee declared = ada().unavailableFor(MONDAY_DAY).preferring(rather);

    declared.noLongerUnavailableFor(MONDAY_DAY).noLongerPreferring(rather);

    assertThat(declared.unavailability()).containsExactly(MONDAY_DAY);
    assertThat(declared.preferences()).containsExactly(rather);
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
