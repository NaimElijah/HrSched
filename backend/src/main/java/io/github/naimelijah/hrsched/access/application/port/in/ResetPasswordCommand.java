package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Objects;

/**
 * A new temporary password for somebody else's account.
 *
 * <p>Distinct from changing one's own password, which takes the current one and is the account
 * owner's alone. An Admin never learns the password they are replacing.
 *
 * <p>{@link #toString()} never shows the password.
 *
 * @param organisation the organisation the account belongs to
 * @param account which account to reset
 * @param temporaryPassword the password it is given, which its owner must replace
 */
public record ResetPasswordCommand(
    OrganisationId organisation, UserId account, String temporaryPassword) {

  public ResetPasswordCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(account, "account must not be null");
    Objects.requireNonNull(temporaryPassword, "temporaryPassword must not be null");
  }

  @Override
  public String toString() {
    return "ResetPasswordCommand[organisation=%s, account=%s, temporaryPassword=redacted]"
        .formatted(organisation.value(), account.value());
  }
}
