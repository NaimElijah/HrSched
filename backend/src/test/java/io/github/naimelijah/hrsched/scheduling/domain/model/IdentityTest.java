package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The identity wrappers scheduling owns, tested as a table for the same reason the kernel ones are.
 *
 * <p>They live here rather than in the shared kernel because nothing outside scheduling has any
 * business naming a shift, a roster, a generation or a swap request.
 */
class IdentityTest {

  private static Stream<Function<UUID, Object>> constructors() {
    return Stream.of(ShiftId::new, RosterId::new, GenerationId::new, SwapRequestId::new);
  }

  private static Stream<Supplier<Object>> factories() {
    return Stream.of(ShiftId::newId, RosterId::newId, GenerationId::newId, SwapRequestId::newId);
  }

  @ParameterizedTest
  @MethodSource("constructors")
  void GivenANullValue_WhenConstructingAnIdentity_ThenItIsRejected(
      Function<UUID, Object> constructor) {
    assertThatThrownBy(() -> constructor.apply(null)).isInstanceOf(NullPointerException.class);
  }

  @ParameterizedTest
  @MethodSource("constructors")
  void GivenTheSameUuid_WhenWrappedTwice_ThenTheTwoIdentitiesAreEqual(
      Function<UUID, Object> constructor) {
    UUID value = UUID.randomUUID();

    assertThat(constructor.apply(value))
        .isEqualTo(constructor.apply(value))
        .hasSameHashCodeAs(constructor.apply(value));
  }

  @ParameterizedTest
  @MethodSource("factories")
  void GivenAFreshIdentity_WhenMintedTwice_ThenTheTwoDiffer(Supplier<Object> factory) {
    assertThat(factory.get()).isNotEqualTo(factory.get());
  }

  @Test
  void GivenOneUuid_WhenWrappedAsBothAShiftAndARoster_ThenTheTwoAreNotTheSameIdentity() {
    UUID value = UUID.randomUUID();

    assertThat((Object) new ShiftId(value)).isNotEqualTo(new RosterId(value));
  }
}
