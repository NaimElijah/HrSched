package io.github.naimelijah.hrsched.shared.domain.model;

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
 * The kernel identity wrappers, which all behave the same way and are therefore tested as a table
 * rather than as four near-identical classes.
 *
 * <p>The one thing a table cannot express is why these wrappers exist at all, so that has a test of
 * its own at the bottom: a {@link UserId} and an {@link EmployeeId} built from the same UUID remain
 * different identities, and the compiler will not let one be passed where the other is expected.
 * See ADR-012.
 */
class IdentityTest {

  private static Stream<Function<UUID, Object>> constructors() {
    return Stream.of(UserId::new, EmployeeId::new, OrganisationId::new, QualificationId::new);
  }

  private static Stream<Supplier<Object>> factories() {
    return Stream.of(
        UserId::newId, EmployeeId::newId, OrganisationId::newId, QualificationId::newId);
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

  /**
   * ADR-012: a user and the personnel record they are constitute different identities on purpose,
   * and are never compared. Wrapping one UUID in both is the nearest thing to a comparison the type
   * system permits, and even that produces two unequal values.
   */
  @Test
  void GivenOneUuid_WhenWrappedAsBothAUserAndAnEmployee_ThenTheTwoAreNotTheSameIdentity() {
    UUID value = UUID.randomUUID();

    assertThat((Object) new UserId(value)).isNotEqualTo(new EmployeeId(value));
  }
}
