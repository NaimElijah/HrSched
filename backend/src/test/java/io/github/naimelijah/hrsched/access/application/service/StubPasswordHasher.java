package io.github.naimelijah.hrsched.access.application.service;

import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import java.util.Objects;

/**
 * Hashes by labelling the password rather than by computing anything, and counts comparisons.
 *
 * <p>Reversible on purpose: a real hash would make these tests measure bcrypt's work factor rather
 * than the rules the services keep.
 *
 * <p>The comparison count is what lets a test assert that a password is checked even when there is
 * no account to check it against - the property that stops an unknown name being refused measurably
 * faster than a known one. Counting is the honest way to assert it; timing it would be a flaky test
 * about the machine it ran on.
 *
 * <p>Named without a {@code Test} suffix so Surefire does not try to run it.
 */
final class StubPasswordHasher implements PasswordHasher {

  private static final String PREFIX = "{stub}";
  private static final int BCRYPT_LIMIT = 72;

  private int comparisons;

  @Override
  public PasswordHash hash(String rawPassword) {
    Objects.requireNonNull(rawPassword, "rawPassword must not be null");
    return new PasswordHash(PREFIX + rawPassword);
  }

  @Override
  public boolean matches(String rawPassword, PasswordHash hash) {
    comparisons++;
    return hash.value().equals(PREFIX + rawPassword);
  }

  @Override
  public int maximumBytes() {
    return BCRYPT_LIMIT;
  }

  /**
   * @return how many password comparisons have been asked for
   */
  int comparisons() {
    return comparisons;
  }
}
