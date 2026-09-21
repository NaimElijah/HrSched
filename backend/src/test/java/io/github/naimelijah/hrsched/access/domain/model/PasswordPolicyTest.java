package io.github.naimelijah.hrsched.access.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The two ends of the length rule, and the fact that they are counted differently.
 *
 * <p>That difference is the whole of this file. A floor counted in bytes would refuse a perfectly
 * long passphrase written in a non-Latin script; a ceiling counted in characters would let one
 * through whose tail bcrypt silently ignores. Both mistakes are invisible without a test that uses
 * multibyte text, so every interesting case below does.
 *
 * <p>Every number and every password here is invented for the test.
 */
class PasswordPolicyTest {

  private static final PasswordPolicy AT_LEAST_TEN = new PasswordPolicy(10, 72);

  @Test
  void GivenAPasswordAboveTheFloorAndBelowTheCeiling_WhenAssessed_ThenNothingIsBroken() {
    assertThat(AT_LEAST_TEN.assess("an invented passphrase")).isEmpty();
  }

  @Test
  void GivenAPasswordExactlyAtTheFloor_WhenAssessed_ThenItIsAccepted() {
    assertThat(AT_LEAST_TEN.assess("a".repeat(10))).isEmpty();
  }

  @Test
  void GivenAPasswordOneShortOfTheFloor_WhenAssessed_ThenItIsTooShort() {
    assertThat(AT_LEAST_TEN.assess("a".repeat(9))).contains(PasswordRejection.TOO_SHORT);
  }

  /**
   * Twelve Han characters are twelve characters and thirty-six bytes. A floor counted in bytes
   * would call this long enough while a person counting what they typed would not.
   */
  @Test
  void GivenAMultibytePassphraseLongEnoughInCharacters_WhenAssessed_ThenItIsAccepted() {
    assertThat(AT_LEAST_TEN.assess("世".repeat(12))).isEmpty();
  }

  /** Nine of them are nine characters, whatever their twenty-seven bytes suggest. */
  @Test
  void GivenAMultibytePassphraseTooShortInCharacters_WhenAssessed_ThenItIsTooShort() {
    assertThat(AT_LEAST_TEN.assess("世".repeat(9))).contains(PasswordRejection.TOO_SHORT);
  }

  /**
   * An emoji is one character to whoever typed it and two UTF-16 units to Java. Counting code
   * points is what makes those agree.
   */
  @Test
  void GivenAPassphraseOfAstralCharacters_WhenAssessed_ThenEachCountsOnce() {
    String tenEmoji = "😀".repeat(10);

    assertThat(tenEmoji.length()).describedAs("twenty UTF-16 units").isEqualTo(20);
    assertThat(AT_LEAST_TEN.assess(tenEmoji)).isEmpty();
    assertThat(AT_LEAST_TEN.assess("😀".repeat(9))).contains(PasswordRejection.TOO_SHORT);
  }

  /** Twenty-five Han characters are seventy-five bytes, which is past what bcrypt reads. */
  @Test
  void GivenAMultibytePassphrasePastTheByteCeiling_WhenAssessed_ThenItIsTooLong() {
    assertThat(AT_LEAST_TEN.assess("世".repeat(25))).contains(PasswordRejection.TOO_LONG);
  }

  @Test
  void GivenAPasswordExactlyAtTheByteCeiling_WhenAssessed_ThenItIsAccepted() {
    assertThat(AT_LEAST_TEN.assess("a".repeat(72))).isEmpty();
  }

  @Test
  void GivenAPasswordOnePastTheByteCeiling_WhenAssessed_ThenItIsTooLong() {
    assertThat(AT_LEAST_TEN.assess("a".repeat(73))).contains(PasswordRejection.TOO_LONG);
  }

  @Test
  void GivenAPolicyWhoseCeilingIsBelowItsFloor_WhenCreated_ThenItIsRefused() {
    assertThatThrownBy(() -> new PasswordPolicy(80, 72))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenAPolicyWithNoFloor_WhenCreated_ThenItIsRefused() {
    assertThatThrownBy(() -> new PasswordPolicy(0, 72))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenANullPassword_WhenAssessed_ThenItIsRefused() {
    assertThatThrownBy(() -> AT_LEAST_TEN.assess(null)).isInstanceOf(NullPointerException.class);
  }
}
