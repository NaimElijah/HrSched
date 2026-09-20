package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import java.util.Objects;

/**
 * Who a verified token belongs to, as of right now.
 *
 * <p>The pending-change flag travels with the actor because it is not a permission and not a role:
 * it is a state in which the account may do exactly two things, change its password and read its
 * own session, and nothing else. Carrying it here is what lets the boundary decide that without
 * asking a second question.
 *
 * @param actor who the caller is
 * @param passwordChangeRequired whether they must change their password before anything else
 */
public record AuthenticatedActor(Actor actor, boolean passwordChangeRequired) {

  public AuthenticatedActor {
    Objects.requireNonNull(actor, "actor must not be null");
  }
}
