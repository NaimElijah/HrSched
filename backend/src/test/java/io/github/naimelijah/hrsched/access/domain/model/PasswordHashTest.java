package io.github.naimelijah.hrsched.access.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The invariants a password hash owns.
 *
 * <p>A hash is not a password, but it is still the material an offline attack needs, so the same
 * rule applies: it may not reach a log by accident. The value here is invented and hashes nothing.
 */
class PasswordHashTest {

  private static final String ENCODED = "{bcrypt}$2a$04$invented.test.value.not.a.real.hash";

  @Test
  void GivenAnEncodedHash_WhenCreatingAPasswordHash_ThenItIsKeptAsGiven() {
    assertThat(new PasswordHash(ENCODED).value()).isEqualTo(ENCODED);
  }

  @Test
  void GivenAPasswordHash_WhenPrinted_ThenTheValueIsNotShown() {
    assertThat(new PasswordHash(ENCODED).toString()).doesNotContain(ENCODED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
  void GivenABlankHash_WhenCreatingAPasswordHash_ThenItIsRefused(String value) {
    assertThatThrownBy(() -> new PasswordHash(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenANullHash_WhenCreatingAPasswordHash_ThenItIsRefused() {
    assertThatThrownBy(() -> new PasswordHash(null)).isInstanceOf(NullPointerException.class);
  }
}
