package io.github.naimelijah.hrsched.access.adapter.out.token;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about an access token that a deployment decides rather than this code.
 *
 * <p>The lifetime is deliberately configuration and not a constant: how long a stolen token stays
 * useful is a risk decision for whoever runs this, the same way every working-time threshold is a
 * decision for whoever is bound by it.
 *
 * <p>The private key is <strong>optional here and mandatory in practice</strong>. It is optional on
 * this record so that a missing one can be refused with a message an operator can act on, rather
 * than as a binding failure naming a field; {@link AccessTokenKey} is where that refusal lives. It
 * comes from the environment as {@code HRSCHED_SECURITY_TOKEN_PRIVATEKEY} and is never written into
 * a properties file, because a committed signing key is a signing key everybody has.
 *
 * <p>{@link #toString()} never prints the key, nor any part of it: a configuration object gets
 * printed - by Boot, a debugger, an exception message - without anybody having meant to, and a
 * signing key in a log is a signing key everybody with the log has. It says only whether one is
 * configured, which is what diagnosing a startup needs.
 *
 * @param issuer the value put in, and required of, the {@code iss} claim
 * @param audience the value put in, and required of, the {@code aud} claim
 * @param lifetime how long an issued token remains valid, strictly positive. The decoder accepts a
 *     token for up to a minute past its expiry, the default clock skew of Spring Security's {@code
 *     JwtTimestampValidator}, so a stolen token stays useful for this long plus that minute
 * @param privateKey a PKCS#8 PEM RSA private key, or null to fall back on {@code ephemeral}
 * @param ephemeral whether a throwaway key may be generated when none is configured; true only for
 *     local development and tests, where tokens surviving a restart would be of no use anyway
 */
@ConfigurationProperties("hrsched.security.token")
public record AccessTokenProperties(
    String issuer, String audience, Duration lifetime, String privateKey, boolean ephemeral) {

  /** Printed in place of a configured private key. */
  private static final String REDACTED = "[redacted]";

  /**
   * @throws NullPointerException if {@code issuer}, {@code audience} or {@code lifetime} is null
   * @throws IllegalArgumentException if {@code issuer} or {@code audience} is blank, or {@code
   *     lifetime} is zero or negative
   */
  public AccessTokenProperties {
    Objects.requireNonNull(issuer, "issuer must not be null");
    Objects.requireNonNull(audience, "audience must not be null");
    Objects.requireNonNull(lifetime, "lifetime must not be null");
    if (issuer.isBlank()) {
      throw new IllegalArgumentException("hrsched.security.token.issuer must not be blank");
    }
    if (audience.isBlank()) {
      throw new IllegalArgumentException("hrsched.security.token.audience must not be blank");
    }
    if (lifetime.isZero() || lifetime.isNegative()) {
      throw new IllegalArgumentException("hrsched.security.token.lifetime must be positive");
    }
  }

  @Override
  public String toString() {
    return "AccessTokenProperties[issuer=%s, audience=%s, lifetime=%s, privateKey=%s, ephemeral=%s]"
        .formatted(issuer, audience, lifetime, privateKey == null ? null : REDACTED, ephemeral);
  }
}
