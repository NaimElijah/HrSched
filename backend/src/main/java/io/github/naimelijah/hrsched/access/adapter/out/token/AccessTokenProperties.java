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
 * @param issuer the value put in, and required of, the {@code iss} claim
 * @param audience the value put in, and required of, the {@code aud} claim
 * @param lifetime how long an issued token remains valid, strictly positive
 * @param privateKey a PKCS#8 PEM RSA private key, or null to fall back on {@code ephemeral}
 * @param ephemeral whether a throwaway key may be generated when none is configured; true only for
 *     local development and tests, where tokens surviving a restart would be of no use anyway
 */
@ConfigurationProperties("hrsched.security.token")
public record AccessTokenProperties(
    String issuer, String audience, Duration lifetime, String privateKey, boolean ephemeral) {

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
}
