package io.github.naimelijah.hrsched.access.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The fake hasher keeps the real adapter's byte limit.
 *
 * <p>A fake that accepts what the adapter refuses tests nothing (backend CLAUDE.md, Testing).
 * {@code BCryptPasswordHasher} refuses to hash a password over its limit, and never matches one: a
 * service test hashing or comparing such a password against a fake that did would pass for a reason
 * production does not share.
 *
 * <p>The over-limit candidate is still counted as a comparison, because the services rely on
 * exactly one comparison per attempt, whatever was typed, so that how long a refusal takes says
 * nothing about why.
 *
 * <p>Every password here is invented for the test.
 */
class StubPasswordHasherTest {

  private final StubPasswordHasher hasher = new StubPasswordHasher();

  @Test
  void GivenAPasswordOverTheLimit_WhenHashing_ThenItIsRefused() {
    String tooLong = overTheLimit();

    assertThatThrownBy(() -> hasher.hash(tooLong)).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The hash is built by hand, in the fake's own form, because no hash of an over-limit password
   * can be made through {@code hash}. Were the limit not checked, the two would be equal.
   */
  @Test
  void GivenAPasswordOverTheLimit_WhenChecked_ThenItDoesNotMatchAndTheComparisonIsCounted() {
    String tooLong = overTheLimit();

    boolean matched = hasher.matches(tooLong, new PasswordHash("{stub}" + tooLong));

    assertThat(matched).describedAs("an over-limit password never matches").isFalse();
    assertThat(hasher.comparisons()).isEqualTo(1);
  }

  @Test
  void GivenAPasswordOfExactlyTheLimit_WhenHashedAndChecked_ThenItMatches() {
    String atTheLimit = "a".repeat(hasher.maximumBytes());

    assertThat(hasher.matches(atTheLimit, hasher.hash(atTheLimit))).isTrue();
  }

  private String overTheLimit() {
    String tooLong = "a".repeat(hasher.maximumBytes() + 1);
    assertThat(tooLong.getBytes(StandardCharsets.UTF_8)).hasSize(hasher.maximumBytes() + 1);
    return tooLong;
  }
}
