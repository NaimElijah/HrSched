package io.github.naimelijah.hrsched.access.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The invariants a sign-in name owns.
 *
 * <p>Two of them are security properties rather than tidiness. Normalisation is what makes the name
 * unique in practice: without it "Ada@Example.com" and "ada@example.com" are two accounts, and the
 * one an administrator disabled is not necessarily the one somebody signs in with. Redaction is
 * what keeps it out of the logs — a sign-in name is personal data, and the type is the only place
 * that can make leaking it hard rather than merely discouraged.
 */
class SignInNameTest {

  /** One character past the longest name accepted, built here rather than written out. */
  private static final String TOO_LONG = "a".repeat(255);

  @Test
  void GivenAMixedCaseNameWithSpaces_WhenCreatingASignInName_ThenItIsTrimmedAndLowerCased() {
    assertThat(new SignInName("  Ada.Lovelace@Example.COM  ").value())
        .isEqualTo("ada.lovelace@example.com");
  }

  /**
   * Turkish is the case this pins down: a locale-sensitive lower-casing turns "I" into a dotless
   * "ı" there, so the same name would normalise differently depending on where the server runs. The
   * machine's locale is therefore set to Turkish for the test, and put back afterwards.
   */
  @Test
  void GivenANameWithAnI_WhenNormalised_ThenTheLocaleOfTheMachineDoesNotChangeIt() {
    Locale before = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    try {
      assertThat(new SignInName("ADMIN").value()).isEqualTo("admin");
    } finally {
      Locale.setDefault(before);
    }
  }

  @Test
  void GivenASignInName_WhenPrinted_ThenTheValueIsNotShown() {
    assertThat(new SignInName("ada@example.com").toString()).doesNotContain("ada@example.com");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
  void GivenABlankName_WhenCreatingASignInName_ThenItIsRefused(String name) {
    assertThatThrownBy(() -> new SignInName(name)).isInstanceOf(InvalidSignInNameException.class);
  }

  @Test
  void GivenANullName_WhenCreatingASignInName_ThenItIsRefused() {
    assertThatThrownBy(() -> new SignInName(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenANameLongerThanTheMaximum_WhenCreatingASignInName_ThenItIsRefused() {
    assertThatThrownBy(() -> new SignInName(TOO_LONG))
        .isInstanceOf(InvalidSignInNameException.class);
  }

  @Test
  void GivenANameOfExactlyTheMaximumLength_WhenCreatingASignInName_ThenItIsAccepted() {
    assertThat(new SignInName("a".repeat(254)).value()).hasSize(254);
  }

  /** A refusal that quoted the name would put it in whatever log caught the exception. */
  @Test
  void GivenARefusedName_WhenReadingTheRefusal_ThenItDoesNotContainTheName() {
    assertThatThrownBy(() -> new SignInName(TOO_LONG)).hasMessageNotContaining(TOO_LONG);
  }

  @Test
  void GivenTwoNamesDifferingOnlyInCase_WhenComparingThem_ThenTheyAreTheSameName() {
    assertThat(new SignInName("Ada@Example.com")).isEqualTo(new SignInName("ada@example.com"));
  }
}
