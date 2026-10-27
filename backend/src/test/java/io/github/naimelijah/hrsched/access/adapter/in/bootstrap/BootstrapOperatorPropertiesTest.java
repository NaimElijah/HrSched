package io.github.naimelijah.hrsched.access.adapter.in.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * What printing the bootstrap properties gives away.
 *
 * <p>The password is the platform operator's, the most privileged credential there is, and it sits
 * in the environment until somebody changes it at first sign-in. A record prints every component by
 * default, and a configuration object gets printed - by Boot, a debugger, an exception message -
 * without anybody having meant to. Not even part of it may appear: a truncated print is still a
 * print.
 *
 * <p>"Any part" is checked as every run of {@value #FRAGMENT} consecutive characters of the
 * password: shorter runs are too common to mean anything.
 *
 * <p>Every value here is invented for the test.
 */
class BootstrapOperatorPropertiesTest {

  /** The shortest run of the secret's characters whose appearance would count as disclosing it. */
  private static final int FRAGMENT = 8;

  /** Invented: a sign-in name, which is not the secret here. */
  private static final String INVENTED_NAME = "invented-operator";

  /** Invented: the configured temporary password, shaped to share nothing with the name. */
  private static final String INVENTED_PASSWORD = "Qz7#WpLk9vRtXm2bNc4H";

  @Test
  void GivenAConfiguredPassword_WhenPrinted_ThenNoPartOfThePasswordAppears() {
    BootstrapOperatorProperties properties =
        new BootstrapOperatorProperties(INVENTED_NAME, INVENTED_PASSWORD);

    String printed = properties.toString();

    assertThat(fragmentsOf(INVENTED_PASSWORD))
        .describedAs("fragments of the password found in: %s", printed)
        .noneMatch(printed::contains);
  }

  private static List<String> fragmentsOf(String secret) {
    return IntStream.rangeClosed(0, secret.length() - FRAGMENT)
        .mapToObj(start -> secret.substring(start, start + FRAGMENT))
        .toList();
  }
}
