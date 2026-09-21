package io.github.naimelijah.hrsched.access.application.port.in;

import java.util.Objects;

/**
 * What somebody submitted to sign in.
 *
 * <p>Neither field is validated here. An unparseable sign-in name has to be refused in exactly the
 * same way as a name nobody holds, so turning it into a domain type at the boundary - where failure
 * would look different - is precisely what must not happen.
 *
 * <p>{@link #toString()} reveals neither. One is a credential and the other identifies a person,
 * and a command that printed itself would put both into whatever log caught it.
 *
 * @param signInName the name submitted, exactly as typed
 * @param password the password submitted
 */
public record SignInCommand(String signInName, String password) {

  public SignInCommand {
    Objects.requireNonNull(signInName, "signInName must not be null");
    Objects.requireNonNull(password, "password must not be null");
  }

  @Override
  public String toString() {
    return "SignInCommand[redacted]";
  }
}
