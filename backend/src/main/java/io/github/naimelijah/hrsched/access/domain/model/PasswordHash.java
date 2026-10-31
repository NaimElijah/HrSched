package io.github.naimelijah.hrsched.access.domain.model;

import java.util.Objects;

/**
 * An already-encoded password hash, opaque to the domain.
 *
 * <p>What algorithm produced it, and what the string looks like inside, is the hasher adapter's
 * business alone. The domain holds it, compares nothing, and never derives anything from it: a
 * password check happens outside, through the driven port that knows the encoding. Keeping the
 * knowledge there is what lets the algorithm change without the domain noticing.
 *
 * <p>Blank is refused with {@link IllegalArgumentException} rather than a domain exception of its
 * own, because a blank hash is not something a user can submit. It can only come from a hasher that
 * returned nothing, which is a defect in this program rather than bad input, and the two deserve
 * different names.
 *
 * <p>{@link #toString()} does not reveal the value. A hash is not a password, but it is the
 * material an offline attack needs, so it is kept out of logs by the same rule.
 *
 * @param value the encoded hash, never {@code null}, never blank
 */
public record PasswordHash(String value) {

  /**
   * @throws NullPointerException if {@code value} is null
   * @throws IllegalArgumentException if {@code value} is blank
   */
  public PasswordHash {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("A password hash must not be blank");
    }
  }

  @Override
  public String toString() {
    return "PasswordHash[redacted]";
  }
}
