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
    assertThat(new ContractTerms(Duration.ofMinutes(2400)).weeklyContractedHours())
        .isEqualTo(Duration.ofMinutes(2400));
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L, -2400L})
  void GivenANonPositiveDuration_WhenConstructingTerms_ThenItIsRejected(long minutes) {
    assertThatThrownBy(() -> new ContractTerms(Duration.ofMinutes(minutes)))
        .isInstanceOf(InvalidContractTermsException.class);
  }

  @Test
  void GivenANullDuration_WhenConstructingTerms_ThenItIsRejected() {
    assertThatThrownBy(() -> new ContractTerms(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenTwoTermsOfTheSameLength_WhenComparingThem_ThenTheyAreEqual() {
    assertThat(new ContractTerms(Duration.ofMinutes(2400)))
        .isEqualTo(new ContractTerms(Duration.ofMinutes(2400)))
        .hasSameHashCodeAs(new ContractTerms(Duration.ofMinutes(2400)));
  }
}
