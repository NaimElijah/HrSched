package io.github.naimelijah.hrsched.access.application.port.in;

import java.util.Objects;

/**
 * A request to replace one's own password.
 *
 * <p>The current password is required even though the caller is already authenticated. A token
 * proves that somebody signed in at some point; it does not prove that whoever is holding the
 * device now is the account's owner, and a password change is exactly the act that would lock the
 * owner out for good.
 *
 * <p>{@link #toString()} reveals neither.
 *
 * @param currentPassword the password in use, to prove this is the account's owner
 * @param newPassword the password they want instead
 */
public record ChangePasswordCommand(String currentPassword, String newPassword) {

  public ChangePasswordCommand {
    Objects.requireNonNull(currentPassword, "currentPassword must not be null");
    Objects.requireNonNull(newPassword, "newPassword must not be null");
  }

  @Override
  public String toString() {
    return "ChangePasswordCommand[redacted]";
  }
}
