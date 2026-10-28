package io.github.naimelijah.hrsched.access.adapter.out.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * What printing the token properties gives away.
 *
 * <p>The private key signs every access token. A record prints every component by default, and
 * Boot, a debugger or an exception message will print a configuration object without anybody having
 * meant to, so the key would land in a log - and a signing key in a log is a signing key everybody
 * with the log has. Not even part of it may appear: a fragment of a PEM narrows the key, and a
 * truncated print is still a print.
 *
 * <p>"Any part" is checked as every run of {@value #FRAGMENT} consecutive characters of the key:
 * shorter runs are too common to mean anything.
 *
 * <p>Every value here is invented for the test, and the "key" is not a key at all - only shaped
 * like one, since the record does not parse it.
 */
class AccessTokenPropertiesTest {

  /** The shortest run of the secret's characters whose appearance would count as disclosing it. */
  private static final int FRAGMENT = 8;

  /** Invented: shaped like a PKCS#8 PEM, and decodes to nothing. */
  private static final String INVENTED_PRIVATE_KEY =
      "-----BEGIN PRIVATE KEY-----\n"
          + "MIIEvQInventedQzX9kLmWpTr7NvBcYhJd2fGs\n"
          + "Rq8Wn3EtUaVyKoZxPi5HbMjDlCe6SgFtQwNr4u\n"
          + "-----END PRIVATE KEY-----\n";

  @Test
  void GivenAConfiguredPrivateKey_WhenPrinted_ThenNoPartOfTheKeyAppears() {
    AccessTokenProperties properties =
        new AccessTokenProperties(
            "invented-issuer",
            "invented-audience",
            Duration.ofMinutes(5),
            INVENTED_PRIVATE_KEY,
            false);

    String printed = properties.toString();

    assertThat(fragmentsOf(INVENTED_PRIVATE_KEY))
        .describedAs("fragments of the private key found in: %s", printed)
        .noneMatch(printed::contains);
  }

  private static List<String> fragmentsOf(String secret) {
    return IntStream.rangeClosed(0, secret.length() - FRAGMENT)
        .mapToObj(start -> secret.substring(start, start + FRAGMENT))
        .toList();
  }
}
