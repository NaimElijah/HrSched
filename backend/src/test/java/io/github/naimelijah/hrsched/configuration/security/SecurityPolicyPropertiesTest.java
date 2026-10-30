package io.github.naimelijah.hrsched.configuration.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.configuration.security.SecurityPolicyProperties.Password;
import io.github.naimelijah.hrsched.configuration.security.SecurityPolicyProperties.Session;
import io.github.naimelijah.hrsched.configuration.security.SecurityPolicyProperties.SignIn;
import java.time.Duration;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What a deployment that left out a session or lockout setting is told.
 *
 * <p>None of these has a default in code, so leaving one out must stop the start with the key to
 * set, rather than with a bare {@code NullPointerException} later, in the wiring that turns them
 * into the domain's policies.
 *
 * <p>Every number here is invented for the test, and none is a recommendation.
 */
class SecurityPolicyPropertiesTest {

  /** Invented: any duration, since only its absence is under test. */
  private static final Duration INVENTED_DURATION = Duration.ofMinutes(7);

  /** Invented: any count, for the same reason. */
  private static final int INVENTED_COUNT = 7;

  private static final SignIn SIGN_IN = new SignIn(INVENTED_COUNT, INVENTED_DURATION);
  private static final Session SESSION = new Session(INVENTED_DURATION, INVENTED_DURATION);
  private static final Password PASSWORD = new Password(INVENTED_COUNT);

  private static Stream<Arguments> bindingsMissingOneValue() {
    return Stream.of(
        missing(
            "hrsched.security.sign-in",
            () -> new SecurityPolicyProperties(null, SESSION, PASSWORD)),
        missing(
            "hrsched.security.session",
            () -> new SecurityPolicyProperties(SIGN_IN, null, PASSWORD)),
        missing(
            "hrsched.security.password",
            () -> new SecurityPolicyProperties(SIGN_IN, SESSION, null)),
        missing(
            "hrsched.security.sign-in.lockout-duration", () -> new SignIn(INVENTED_COUNT, null)),
        missing(
            "hrsched.security.session.absolute-lifetime",
            () -> new Session(null, INVENTED_DURATION)),
        missing(
            "hrsched.security.session.idle-lifetime", () -> new Session(INVENTED_DURATION, null)));
  }

  private static Arguments missing(String key, ThrowingCallable binding) {
    return Arguments.of(key, binding);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("bindingsMissingOneValue")
  void GivenAMissingSetting_WhenBound_ThenTheRefusalNamesItsKey(
      String key, ThrowingCallable binding) {
    assertThatThrownBy(binding).isInstanceOf(NullPointerException.class).hasMessageContaining(key);
  }
}
