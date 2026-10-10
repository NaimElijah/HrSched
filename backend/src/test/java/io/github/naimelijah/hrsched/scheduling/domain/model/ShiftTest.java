package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The invariants a shift owns.
 *
 * <p>A shift is an entity rather than a record on purpose: two shifts covering the same period are
 * still two different shifts, so equality is identity and not value.
 */
class ShiftTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final TimeRange MONDAY_DAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));

  private static Shift shift(int headcount) {
    return new Shift(ShiftId.newId(), ACME, MONDAY_DAY, ZoneOffset.UTC, headcount, Set.of());
  }

  @Test
  void GivenAHeadcountOfOne_WhenConstructingAShift_ThenItIsAccepted() {
    assertThat(shift(1).requiredHeadcount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void GivenAHeadcountBelowOne_WhenConstructingAShift_ThenItIsRejected(int headcount) {
    assertThatThrownBy(() -> shift(headcount)).isInstanceOf(InvalidHeadcountException.class);
  }

  @Test
  void GivenANullIdentity_WhenConstructingAShift_ThenItIsRejected() {
    assertThatThrownBy(() -> new Shift(null, ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenANullOrganisation_WhenConstructingAShift_ThenItIsRejected() {
    assertThatThrownBy(
            () -> new Shift(ShiftId.newId(), null, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of()))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * A shift whose days could be counted in no zone could be checked against no daily rule (R36).
   */
  @Test
  void GivenANullZone_WhenConstructingAShift_ThenItIsRejected() {
    assertThatThrownBy(() -> new Shift(ShiftId.newId(), ACME, MONDAY_DAY, null, 1, Set.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenANullPeriod_WhenConstructingAShift_ThenItIsRejected() {
    assertThatThrownBy(() -> new Shift(ShiftId.newId(), ACME, null, ZoneOffset.UTC, 1, Set.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenNullQualifications_WhenConstructingAShift_ThenItIsRejected() {
    assertThatThrownBy(() -> new Shift(ShiftId.newId(), ACME, MONDAY_DAY, ZoneOffset.UTC, 1, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenNoRequiredQualifications_WhenConstructingAShift_ThenItIsAccepted() {
    assertThatCode(() -> shift(1)).doesNotThrowAnyException();
    assertThat(shift(1).requiredQualifications()).isEmpty();
  }

  @Test
  void GivenAMutableSetOfQualifications_WhenConstructingAShift_ThenLaterChangesAreNotSeen() {
    Set<QualificationId> required = new HashSet<>(Set.of(QualificationId.newId()));
    Shift shift = new Shift(ShiftId.newId(), ACME, MONDAY_DAY, ZoneOffset.UTC, 1, required);

    required.add(QualificationId.newId());

    assertThat(shift.requiredQualifications()).hasSize(1);
  }

  @Test
  void GivenTwoShiftsSharingAPeriodAndHeadcount_WhenComparingThem_ThenIdentityDecides() {
    Shift one = shift(1);
    Shift another = shift(1);

    assertThat(one).isNotEqualTo(another);
  }

  @Test
  void GivenTwoShiftsSharingAnIdentity_WhenComparingThem_ThenTheyAreTheSameShift() {
    ShiftId id = ShiftId.newId();
    Shift original = new Shift(id, ACME, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of());
    Shift rescheduled =
        new Shift(
            id,
            ACME,
            new TimeRange(
                Instant.parse("2026-09-07T09:00:00Z"), Instant.parse("2026-09-07T17:00:00Z")),
            ZoneOffset.UTC,
            2,
            Set.of());

    assertThat(original).isEqualTo(rescheduled).hasSameHashCodeAs(rescheduled);
  }

  @Test
  void GivenAShiftComparedToAnotherType_WhenComparingThem_ThenTheyDiffer() {
    assertThat(shift(1)).isNotEqualTo("a shift");
  }
}
