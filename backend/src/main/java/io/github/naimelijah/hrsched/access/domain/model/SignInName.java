package io.github.naimelijah.hrsched.access.domain.model;

import java.util.Locale;
import java.util.Objects;

/**
 * The name an account signs in with: platform-unique, and normalised so that it stays that way.
 *
 * <p>Normalisation is not tidiness. Uniqueness is enforced on the stored value, so without a single
 * normal form "Ada@Example.com" and "ada@example.com" are two accounts, and the one an
 * administrator disabled need not be the one somebody signs in with. Trimming and lower-casing here
 * means every caller — sign-in, account creation, the unique index — is comparing the same thing.
 *
 * <p>The lower-casing is pinned to {@link Locale#ROOT} rather than the machine's default. A Turkish
 * locale maps "I" to a dotless "ı", so the same submitted name would otherwise normalise
 * differently depending on where the server happens to run, and an account created on one host
 * could not be signed into from another.
 *
 * <p>{@link #toString()} does not reveal the value. A sign-in name identifies a person, so it is
 * personal data that must never reach a log by accident; a type that cannot print itself makes that
 * hard rather than merely discouraged.
 *
 * @param value the normalised name, never {@code null}, never blank
 */
public record SignInName(String value) {

  /**
   * The longest name accepted. 254 characters is the longest an email address can be, and is used
   * here as the bound because an email address is the longest thing this name might have to hold.
   * Whether it must <em>be</em> an email address is undecided — Q28 — and this constant does not
   * decide it.
   */
  private static final int MAXIMUM_LENGTH = 254;

  public SignInName {
    Objects.requireNonNull(value, "value must not be null");
    value = value.strip().toLowerCase(Locale.ROOT);
    if (value.isEmpty() || value.length() > MAXIMUM_LENGTH) {
      throw new InvalidSignInNameException();
    }
  }

  @Override
  public String toString() {
    return "SignInName[redacted]";
  }
}
