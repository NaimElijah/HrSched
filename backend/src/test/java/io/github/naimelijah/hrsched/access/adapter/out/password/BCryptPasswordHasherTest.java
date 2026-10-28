package io.github.naimelijah.hrsched.access.adapter.out.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * What the hasher promises: a password can be checked against its hash and never read back out of
 * it, and the stored form says which algorithm made it.
 *
 * <p>The strength here is the cheapest bcrypt allows, because these tests hash a great many
 * passwords and the work factor is the point of bcrypt rather than of this test. A deployment uses
 * a real one; the number is configuration either way.
 *
 * <p>Every password below is invented for the test.
 */
class BCryptPasswordHasherTest {

  private static final int TEST_STRENGTH = 4;

  private static final String CHOSEN = "an invented password for a test";

  private final BCryptPasswordHasher hasher =
      new BCryptPasswordHasher(new PasswordHashingProperties(TEST_STRENGTH));

  @Test
  void GivenAPassword_WhenHashedAndCheckedAgainstItsHash_ThenItMatches() {
    PasswordHash hash = hasher.hash(CHOSEN);

    assertThat(hasher.matches(CHOSEN, hash)).isTrue();
  }

  @Test
  void GivenAnotherPassword_WhenCheckedAgainstTheHash_ThenItDoesNotMatch() {
    PasswordHash hash = hasher.hash(CHOSEN);

    assertThat(hasher.matches("something else entirely", hash)).isFalse();
  }

  /**
   * The stored hash carries its algorithm, which is what lets the algorithm change later without
   * migrating anybody: an old hash still says how to check it.
   */
  @Test
  void GivenAHashedPassword_WhenStored_ThenItNamesTheAlgorithmThatMadeIt() {
    assertThat(hasher.hash(CHOSEN).value()).startsWith("{bcrypt}$2");
  }

  /** Two hashes of one password differ, because each is salted. */
  @Test
  void GivenTheSamePasswordTwice_WhenHashed_ThenTheTwoHashesDiffer() {
    assertThat(hasher.hash(CHOSEN).value()).isNotEqualTo(hasher.hash(CHOSEN).value());
  }

  /**
   * Bcrypt reads no further than its limit, so a password longer than it is a password whose tail
   * does nothing. Refused here rather than quietly shortened, because somebody choosing a long
   * passphrase has every reason to believe all of it counts.
   */
  @Test
  void GivenAPasswordLongerThanTheLimit_WhenHashing_ThenItIsRefusedWithoutEchoingIt() {
    // Three bytes per character, so the byte limit is passed well before the character count
    // suggests it: a check counting characters would let this through.
    String tooLong = "世".repeat(30);
    assertThat(tooLong.getBytes(StandardCharsets.UTF_8).length)
        .isGreaterThan(hasher.maximumBytes());

    assertThatThrownBy(() -> hasher.hash(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(
            refused ->
                assertThat(stackTraceOf(refused))
                    .describedAs("a refusal must not put the password in a log")
                    .doesNotContain(tooLong));
  }

  @Test
  void GivenAPasswordOfExactlyTheLimit_WhenHashing_ThenItIsAccepted() {
    String atTheLimit = "a".repeat(hasher.maximumBytes());

    assertThat(hasher.matches(atTheLimit, hasher.hash(atTheLimit))).isTrue();
  }

  /**
   * Verifying must keep the limit hashing keeps. Bcrypt reads only the first 72 bytes, so a check
   * that let a longer candidate through would accept any tail after a stored password of exactly
   * the limit: one password, and every extension of it, would all sign in. False rather than an
   * exception, because the candidate is whatever somebody typed at sign-in. That the exact password
   * still matches is {@code GivenAPasswordOfExactlyTheLimit_WhenHashing_ThenItIsAccepted}.
   */
  @Test
  void
      GivenAHashOfAPasswordOfExactlyTheLimit_WhenCheckingThatPasswordPlusOneByte_ThenItDoesNotMatch() {
    String atTheLimit = "a".repeat(hasher.maximumBytes());
    PasswordHash hash = hasher.hash(atTheLimit);
    String oneBytePast = atTheLimit + "b";
    assertThat(oneBytePast.getBytes(StandardCharsets.UTF_8)).hasSize(hasher.maximumBytes() + 1);

    assertThat(hasher.matches(oneBytePast, hash))
        .describedAs("a byte past the limit is a different password, not the same one")
        .isFalse();
  }

  @Test
  void GivenANullPassword_WhenHashing_ThenItIsRefused() {
    assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(NullPointerException.class);
  }

  private static String stackTraceOf(Throwable thrown) {
    StringWriter written = new StringWriter();
    thrown.printStackTrace(new PrintWriter(written));
    return written.toString();
  }
}
