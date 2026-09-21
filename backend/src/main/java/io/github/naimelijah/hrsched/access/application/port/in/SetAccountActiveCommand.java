package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Objects;

/**
 * Whether an account may act at all.
 *
 * <p>A boolean rather than two use cases, because disabling and restoring are the same decision
 * made twice and an Admin screen offers them as one control.
 *
 * @param organisation the organisation the account belongs to
 * @param account which account to change
 * @param active whether it may sign in and act
 */
public record SetAccountActiveCommand(OrganisationId organisation, UserId account, boolean active) {

  public SetAccountActiveCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(account, "account must not be null");
  }
}
