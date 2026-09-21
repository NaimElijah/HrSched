package io.github.naimelijah.hrsched.access.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * What a password somebody chooses for themselves has to satisfy.
 *
 * <p>Length and nothing else. No composition rules, no forbidden characters, no forced digits:
 * those make passwords harder to remember without making them harder to guess, which is why current
 * guidance drops them. What is left is a floor on length, and a ceiling the algorithm imposes.
 *
 * <p>The two limits are counted differently on purpose. The floor is in <em>code points</em>,
 * because that is what a person typing a passphrase would call its length — counting UTF-16 units
 * would make a password of emoji look twice as long as it is. The ceiling is in <em>UTF-8
 * bytes</em>, because that is what bcrypt actually reads, and a passphrase in a non-Latin script
 * reaches it far sooner than its character count suggests.
 *
 * <p>Neither number is a constant here. The floor is configuration; the ceiling comes from whatever
 * hasher is in use, through its own port.
 *
 * <p>Nothing in this class ever holds, returns or reports the candidate.
 *
 * @param minimumLength the fewest code points a password may have
 * @param maximumBytes the most UTF-8 bytes it may have, which is the hasher's own limit
 */
public record PasswordPolicy(int minimumLength, int maximumBytes) {

  public PasswordPolicy {
    if (minimumLength < 1) {
      throw new IllegalArgumentException("A password must be at least one character long");
    }
    if (maximumBytes < minimumLength) {
      throw new IllegalArgumentException(
          "A password policy whose maximum is below its minimum can never be satisfied");
    }
  }

  /**
   * @param candidate the password somebody chose, never {@code null}
   * @return the rule it breaks, or empty if it breaks none
   */
  public Optional<PasswordRejection> assess(String candidate) {
    Objects.requireNonNull(candidate, "candidate must not be null");
    if (candidate.codePointCount(0, candidate.length()) < minimumLength) {
      return Optional.of(PasswordRejection.TOO_SHORT);
    }
    if (candidate.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
      return Optional.of(PasswordRejection.TOO_LONG);
    }
    return Optional.empty();
  }
}
