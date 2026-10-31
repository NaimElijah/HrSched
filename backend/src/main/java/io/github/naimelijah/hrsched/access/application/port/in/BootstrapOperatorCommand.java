package io.github.naimelijah.hrsched.access.application.port.in;

import java.util.Objects;

/**
 * What the first platform operator is to be created with.
 *
 * <p>Both fields come from a deployment's configuration rather than from a request: there is nobody
 * signed in when this runs, and the account being created is the one that would otherwise have to
 * create it.
 *
 * <p>{@link #toString()} reveals neither. The password is a credential, and the name identifies a
 * person; a command that printed itself into a startup log would put both there.
 *
 * @param signInName the name the operator will sign in with
 * @param temporaryPassword the password they must change at first sign-in
 */
public record BootstrapOperatorCommand(String signInName, String temporaryPassword) {

  /**
   * @throws NullPointerException if {@code signInName} or {@code temporaryPassword} is null
   */
  public BootstrapOperatorCommand {
    Objects.requireNonNull(signInName, "signInName must not be null");
    Objects.requireNonNull(temporaryPassword, "temporaryPassword must not be null");
  }

  @Override
  public String toString() {
    return "BootstrapOperatorCommand[redacted]";
  }
}
