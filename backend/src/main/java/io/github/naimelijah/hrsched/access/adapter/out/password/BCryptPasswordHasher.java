package io.github.naimelijah.hrsched.access.adapter.out.password;

import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Driven adapter hashing passwords with bcrypt, behind a delegating encoder.
 *
 * <p>The delegating encoder is what makes a later change of algorithm survivable. Every hash it
 * writes is prefixed with the id of the function that made it, so a stored hash says how to check
 * itself; changing the default later leaves every existing hash verifiable and nobody locked out,
 * with no migration to run. Without it, the algorithm would be an unwritten assumption about every
 * row in the table.
 *
 * <p>Bcrypt reads no further than {@value #MAXIMUM_PASSWORD_BYTES} bytes, and this adapter holds
 * that limit on both sides. Hashing refuses a longer password. Spring Security 7.1.1 would too —
 * verified in the jar, where {@code BCrypt} carries the message "password cannot be more than 72
 * bytes" — but this adapter checks first anyway, so the refusal is this port's contract rather than
 * a library's, and so that nothing can put the password itself into an exception thrown from
 * somewhere that does not know what it is. Verifying is where the library does not hold the limit:
 * its check skips the length test and compares only the first 72 bytes, so a stored password of
 * exactly the limit would match every extension of it. Here a longer candidate simply does not
 * match.
 *
 * <p>The limit is counted in bytes, not characters. A passphrase of Han characters costs three
 * bytes each, so a check counting characters would let a password through whose tail bcrypt
 * silently ignored.
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

  /** Bcrypt's own limit: bytes beyond this are not read, so a password past it is not all used. */
  private static final int MAXIMUM_PASSWORD_BYTES = 72;

  /** The id written into every hash this produces, and the one it is checked back with. */
  private static final String BCRYPT = "bcrypt";

  private final PasswordEncoder encoder;

  /**
   * @param properties the bcrypt work factor to hash with
   */
  public BCryptPasswordHasher(PasswordHashingProperties properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    this.encoder =
        new DelegatingPasswordEncoder(
            BCRYPT, Map.of(BCRYPT, new BCryptPasswordEncoder(properties.bcryptStrength())));
  }

  @Override
  public PasswordHash hash(String rawPassword) {
    Objects.requireNonNull(rawPassword, "rawPassword must not be null");
    if (exceedsTheLimit(rawPassword)) {
      // Says the limit and nothing about what was submitted: this message reaches a log.
      throw new IllegalArgumentException(
          "A password must be at most %d bytes long".formatted(MAXIMUM_PASSWORD_BYTES));
    }
    return new PasswordHash(encoder.encode(rawPassword));
  }

  @Override
  public boolean matches(String rawPassword, PasswordHash hash) {
    Objects.requireNonNull(rawPassword, "rawPassword must not be null");
    Objects.requireNonNull(hash, "hash must not be null");
    // False, not an exception: the candidate is whatever somebody typed. Answered without a
    // comparison, which says only that the candidate is too long - nothing about any account.
    if (exceedsTheLimit(rawPassword)) {
      return false;
    }
    return encoder.matches(rawPassword, hash.value());
  }

  private static boolean exceedsTheLimit(String rawPassword) {
    return rawPassword.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_PASSWORD_BYTES;
  }

  @Override
  public int maximumBytes() {
    return MAXIMUM_PASSWORD_BYTES;
  }
}
