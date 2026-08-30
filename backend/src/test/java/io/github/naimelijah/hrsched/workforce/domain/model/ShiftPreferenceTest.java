package io.github.naimelijah.hrsched.workforce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The invariants a stated preference owns.
 *
 * <p>A preference is soft by construction: it carries a span and a strength and nothing else, so
 * there is no field through which it could ever block an assignment. That is asserted rather than
 * assumed, because the day it gains one is the day preferences quietly become constraints.
 */
class ShiftPreferenceTest {

  private static final TimeRange MONDAY_DAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));

  @ParameterizedTest
  @EnumSource(PreferenceStrength.class)
  void GivenAnyStrength_WhenStatingAPreference_ThenItIsAccepted(PreferenceStrength strength) {
    assertThatCode(() -> new ShiftPreference(MONDAY_DAY, strength)).doesNotThrowAnyException();
  }

  @Test
  void GivenANullPeriod_WhenStatingAPreference_ThenItIsRejected() {
    assertThatThrownBy(() -> new ShiftPreference(null, PreferenceStrength.PREFERS))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenANullStrength_WhenStatingAPreference_ThenItIsRejected() {
    assertThatThrownBy(() -> new ShiftPreference(MONDAY_DAY, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenTwoPreferencesSharingBothFields_WhenComparingThem_ThenTheyAreEqual() {
    assertThat(new ShiftPreference(MONDAY_DAY, PreferenceStrength.PREFERS))
        .isEqualTo(new ShiftPreference(MONDAY_DAY, PreferenceStrength.PREFERS));
  }

  @Test
  void GivenTwoPreferencesDifferingInStrength_WhenComparingThem_ThenTheyDiffer() {
    assertThat(new ShiftPreference(MONDAY_DAY, PreferenceStrength.PREFERS))
        .isNotEqualTo(new ShiftPreference(MONDAY_DAY, PreferenceStrength.PREFERS_NOT));
  }

  @Test
  void GivenAPreference_WhenExamined_ThenItCarriesNoPowerToBlockAnAssignment() {
    assertThat(ShiftPreference.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactlyInAnyOrder("period", "strength");
  }
}
