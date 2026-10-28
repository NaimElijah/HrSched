package io.github.naimelijah.hrsched.access.adapter.out.password;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Every check costs one bcrypt comparison, whatever the candidate.
 *
 * <p>A candidate the adapter can refuse without looking - longer than bcrypt reads, or empty - must
 * still pay for a comparison before it is refused. Answering it at once makes it measurably faster
 * than a wrong password of ordinary length, and how long a refusal takes must say nothing about why
 * it was refused.
 *
 * <p>Spring Security's encoders answer an empty candidate false without comparing ({@code
 * AbstractValidatingPasswordEncoder.matches}, 7.1.1), so reaching the bcrypt encoder at all means a
 * comparison ran. The count is taken there, on the encoder this adapter hands its delegating
 * encoder, through the package-private constructor that takes it. Counting rather than timing,
 * because timing would be a test about the machine it ran on.
 *
 * <p>Every password here is invented for the test.
 */
class BCryptPasswordHasherComparisonTest {

  /** The cheapest work factor bcrypt allows; the work factor is not what is under test. */
  private static final int TEST_STRENGTH = 4;

  private final CountingEncoder bcrypt =
      new CountingEncoder(new BCryptPasswordEncoder(TEST_STRENGTH));
  private final BCryptPasswordHasher hasher = new BCryptPasswordHasher(bcrypt);

  @Test
  void GivenACandidateOverTheLimit_WhenChecked_ThenOneComparisonRunsAndItDoesNotMatch() {
    PasswordHash stored = hasher.hash("an invented password for a test");
    String tooLong = "a".repeat(hasher.maximumBytes() + 1);
    assertThat(tooLong.getBytes(StandardCharsets.UTF_8)).hasSize(hasher.maximumBytes() + 1);

    boolean matched = hasher.matches(tooLong, stored);

    assertThat(bcrypt.comparisons()).describedAs("bcrypt comparisons made").isEqualTo(1);
    assertThat(matched).isFalse();
  }

  @Test
  void GivenAnEmptyCandidate_WhenChecked_ThenOneComparisonRunsAndItDoesNotMatch() {
    PasswordHash stored = hasher.hash("an invented password for a test");

    boolean matched = hasher.matches("", stored);

    assertThat(bcrypt.comparisons()).describedAs("bcrypt comparisons made").isEqualTo(1);
    assertThat(matched).isFalse();
  }

  /** The baseline the two above are held to: an ordinary wrong password costs one comparison. */
  @Test
  void GivenAnOrdinaryWrongCandidate_WhenChecked_ThenOneComparisonRuns() {
    PasswordHash stored = hasher.hash("an invented password for a test");

    hasher.matches("not the invented password", stored);

    assertThat(bcrypt.comparisons()).isEqualTo(1);
  }

  /** Passes everything to the real bcrypt encoder, counting the comparisons it actually makes. */
  private static final class CountingEncoder implements PasswordEncoder {

    private final PasswordEncoder real;
    private int comparisons;

    CountingEncoder(PasswordEncoder real) {
      this.real = real;
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return real.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      // An empty candidate is answered by the real encoder without comparing, so forwarding one
      // is not a comparison and is not counted.
      if (rawPassword != null && !rawPassword.isEmpty()) {
        comparisons++;
      }
      return real.matches(rawPassword, encodedPassword);
    }

    int comparisons() {
      return comparisons;
    }
  }
}
