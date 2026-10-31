package io.github.naimelijah.hrsched.access.application.port.in;

import java.util.Objects;

/**
 * What it takes to onboard an organisation: a name, and somebody who can administer it.
 *
 * <p>The first Admin is part of onboarding rather than a second step, because an organisation
 * nobody can administer is not usable and the operator is refused everything inside one (ADR-030) -
 * so there would be no way to add one afterwards. The two are created in one transaction, and a
 * refused account leaves no organisation behind.
 *
 * <p>{@link #toString()} shows the organisation's name and nothing else. The temporary password is
 * a credential, and the sign-in name identifies a person.
 *
 * @param name what the organisation is called
 * @param firstAdminSignInName the name its first Admin signs in with
 * @param firstAdminTemporaryPassword the password they must change at first sign-in
 */
public record OnboardOrganisationCommand(
    String name, String firstAdminSignInName, String firstAdminTemporaryPassword) {

  /**
   * @throws NullPointerException if {@code name}, {@code firstAdminSignInName} or {@code
   *     firstAdminTemporaryPassword} is null
   */
  public OnboardOrganisationCommand {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(firstAdminSignInName, "firstAdminSignInName must not be null");
    Objects.requireNonNull(
        firstAdminTemporaryPassword, "firstAdminTemporaryPassword must not be null");
  }

  @Override
  public String toString() {
    return "OnboardOrganisationCommand[name=%s, firstAdmin=redacted]".formatted(name);
  }
}
