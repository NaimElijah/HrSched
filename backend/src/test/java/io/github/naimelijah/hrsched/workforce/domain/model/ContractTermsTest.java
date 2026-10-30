package io.github.naimelijah.hrsched.workforce.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The invariant contracted terms own.
 *
 * <p>EVERY FIGURE BELOW IS INVENTED. Contracted hours are a term of an individual employment
 * agreement, not a statutory threshold, and nothing here is taken from either.
 */
class ContractTermsTest {

  @Test
  void GivenAPositiveDuration_WhenConstructingTerms_ThenItIsAccepted() {
    // Invented: a contracted week, a term of an individual agreement.
    assertThat(new ContractTerms(Duration.ofMinutes(2401)).weeklyContractedHours())
        .isEqualTo(Duration.ofMinutes(2401));
  }

  @ParameterizedTest
  // Invented: zero, the smallest step below it, and a negative week.
  @ValueSource(longs = {0L, -1L, -2401L})
  void GivenANonPositiveDuration_WhenConstructingTerms_ThenItIsRejected(long minutes) {
    assertThatThrownBy(() -> new ContractTerms(Duration.ofMinutes(minutes)))
        .isInstanceOf(InvalidContractTermsException.class);
  }

  /**
   * Contracted hours say things about a person's household and health, and a message travels
   * further than the record it describes. The invented -1234 minutes is -20 hours 34 minutes, so
   * neither the minutes, the hours nor the duration's own rendering may appear.
   */
  @Test
  void GivenInvalidHours_WhenTheyAreRefused_ThenTheMessageDoesNotRepeatThem() {
    Duration invalid = Duration.ofMinutes(-1234);

    assertThatThrownBy(() -> new ContractTerms(invalid))
        .isInstanceOf(InvalidContractTermsException.class)
        .message()
        .doesNotContain(invalid.toString())
        .doesNotContain("1234")
        .doesNotContain("20");
  }

  @Test
  void GivenANullDuration_WhenConstructingTerms_ThenItIsRejected() {
    assertThatThrownBy(() -> new ContractTerms(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenTwoTermsOfTheSameLength_WhenComparingThem_ThenTheyAreEqual() {
    // Invented: a contracted week, a term of an individual agreement.
    assertThat(new ContractTerms(Duration.ofMinutes(2401)))
        .isEqualTo(new ContractTerms(Duration.ofMinutes(2401)))
        .hasSameHashCodeAs(new ContractTerms(Duration.ofMinutes(2401)));
  }
}
