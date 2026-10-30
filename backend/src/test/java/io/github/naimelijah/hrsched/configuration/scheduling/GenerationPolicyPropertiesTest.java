package io.github.naimelijah.hrsched.configuration.scheduling;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * What a deployment that forgot how long a generation may stay pending is told.
 *
 * <p>The value has no default in code, so leaving it out must stop the start with the key to set,
 * rather than with a bare {@code NullPointerException} later, in the wiring that reads it.
 */
class GenerationPolicyPropertiesTest {

  @Test
  void GivenNoLostAfter_WhenBound_ThenTheRefusalNamesTheKey() {
    assertThatThrownBy(() -> new GenerationPolicyProperties(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("hrsched.generation.lost-after");
  }
}
