package io.github.naimelijah.hrsched.access.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The invariants a lockout policy owns.
 *
 * <p>Both numbers are configuration and neither has a default here: how many failures are too many,
 * and how long a lock lasts, are decisions for whoever deploys this, not for this class. Every
 * number below is invented for the test.
 */
class LockoutPolicyTest {

  @Test
  void GivenAThresholdAndADuration_WhenCreatingAPolicy_ThenItIsAccepted() {
    LockoutPolicy policy = new LockoutPolicy(3, Duration.ofMinutes(7));

    assertThat(policy.threshold()).isEqualTo(3);
    assertThat(policy.duration()).isEqualTo(Duration.ofMinutes(7));
  }

  /** A threshold of one locks on the first failure, which is severe but coherent. */
  @Test
  void GivenAThresholdOfOne_WhenCreatingAPolicy_ThenItIsAccepted() {
    assertThat(new LockoutPolicy(1, Duration.ofMinutes(7)).threshold()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, -10})
  void GivenAThresholdBelowOne_WhenCreatingAPolicy_ThenItIsRefused(int threshold) {
    assertThatThrownBy(() -> new LockoutPolicy(threshold, Duration.ofMinutes(7)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenADurationOfZero_WhenCreatingAPolicy_ThenItIsRefused() {
    assertThatThrownBy(() -> new LockoutPolicy(3, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenANegativeDuration_WhenCreatingAPolicy_ThenItIsRefused() {
    assertThatThrownBy(() -> new LockoutPolicy(3, Duration.ofMinutes(-7)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenANullDuration_WhenCreatingAPolicy_ThenItIsRefused() {
    assertThatThrownBy(() -> new LockoutPolicy(3, null)).isInstanceOf(NullPointerException.class);
  }
}
