package io.github.naimelijah.hrsched.access.adapter.out.token;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.access.application.port.out.RefreshTokenSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * What a refresh token is made of, and what is kept of it.
 *
 * <p>The digest is the property that matters: a database that leaks must hand over nothing anybody
 * can present, so what is stored is the token's SHA-256 digest and never the token (V4, ADR-028).
 * The expected digest is computed here with the JDK's own {@link MessageDigest}, so a change of
 * algorithm, or storing the bytes as they came, fails this test rather than being agreed with.
 *
 * <p>Randomness is not asserted statistically. Two tokens differing, and each carrying the number
 * of bytes the adapter promises, is what a caller can observe; anything more would be a test of the
 * JDK's generator.
 */
class SecureRandomSessionSecretsTest {

  /**
   * The 256 bits the adapter's javadoc promises, which is a design choice rather than a statute.
   */
  private static final int TOKEN_BYTES = 32;

  /** SHA-256's output length. */
  private static final int SHA_256_BYTES = 32;

  private final SecureRandomSessionSecrets secrets = new SecureRandomSessionSecrets();

  @Test
  void GivenAGeneratedToken_WhenItsDigestIsRead_ThenItIsTheSha256OfTheValue() throws Exception {
    RefreshTokenSecret generated = secrets.generate();

    byte[] expected =
        MessageDigest.getInstance("SHA-256")
            .digest(generated.value().getBytes(StandardCharsets.UTF_8));
    assertThat(generated.digest()).hasSize(SHA_256_BYTES).isEqualTo(expected);
  }

  @Test
  void GivenAGeneratedToken_WhenItsDigestIsRead_ThenItIsNotTheValueItself() {
    RefreshTokenSecret generated = secrets.generate();

    assertThat(generated.digest())
        .describedAs("what is stored must not be what the client presents")
        .isNotEqualTo(generated.value().getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void GivenAPresentedValue_WhenDigested_ThenItMatchesTheDigestTheTokenWasGeneratedWith() {
    RefreshTokenSecret generated = secrets.generate();

    assertThat(secrets.digestOf(generated.value()))
        .describedAs("recognising a token is digesting it again and finding that")
        .isEqualTo(generated.digest());
  }

  @Test
  void GivenTwoGeneratedTokens_WhenCompared_ThenTheyDiffer() {
    RefreshTokenSecret one = secrets.generate();
    RefreshTokenSecret other = secrets.generate();

    assertThat(other.value()).isNotEqualTo(one.value());
    assertThat(other.digest()).isNotEqualTo(one.digest());
  }

  @Test
  void GivenAGeneratedToken_WhenDecoded_ThenItCarriesThirtyTwoRandomBytes() {
    String value = secrets.generate().value();

    assertThat(Base64.getUrlDecoder().decode(value)).hasSize(TOKEN_BYTES);
  }
}
