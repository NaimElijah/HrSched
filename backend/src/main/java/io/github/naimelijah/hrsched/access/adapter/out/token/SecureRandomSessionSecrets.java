package io.github.naimelijah.hrsched.access.adapter.out.token;

import io.github.naimelijah.hrsched.access.application.port.out.RefreshTokenSecret;
import io.github.naimelijah.hrsched.access.application.port.out.SessionSecrets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Driven adapter making refresh tokens out of the JDK's own randomness.
 *
 * <p>A refresh token carries no structure and means nothing: it is {@value #TOKEN_BYTES} random
 * bytes and is recognised only by being found. That is deliberate. A token whose contents said
 * anything - a session id, an account, a timestamp - would be a second, weaker statement of what
 * the stored row already says, and something an attacker could reason about.
 *
 * <p>One {@link SecureRandom} for the life of the process, because seeding one per call is slower
 * and no better.
 *
 * <p>Stored as a SHA-256 digest. Preimage resistance is the only property needed here, and the
 * input is {@value #TOKEN_BYTES} bytes of uniform randomness rather than a password, so there is
 * nothing to guess and no work factor to add - a password hash would cost every renewal something
 * for no gain.
 */
@Component
public class SecureRandomSessionSecrets implements SessionSecrets {

  /** 256 bits: far past guessing, and it is only ever copied around, never typed. */
  private static final int TOKEN_BYTES = 32;

  private static final String DIGEST_ALGORITHM = "SHA-256";

  private final SecureRandom random = new SecureRandom();

  @Override
  public RefreshTokenSecret generate() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return new RefreshTokenSecret(value, digestOf(value));
  }

  @Override
  public byte[] digestOf(String value) {
    Objects.requireNonNull(value, "value must not be null");
    try {
      return MessageDigest.getInstance(DIGEST_ALGORITHM)
          .digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("This JVM does not provide " + DIGEST_ALGORITHM, impossible);
    }
  }
}
