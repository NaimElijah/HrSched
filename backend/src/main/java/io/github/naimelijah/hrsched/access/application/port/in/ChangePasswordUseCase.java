package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;

/**
 * Driving port: changing one's own password.
 *
 * <p>Self-service only. There is no parameter naming whose password to change, so this cannot be
 * turned into an administrator's tool by passing a different identifier; resetting somebody else's
 * is a different act with a different name.
 */
public interface ChangePasswordUseCase {

  /**
   * @param actor whoever is changing their own password, never {@code null}
   * @param command the current and new passwords, never {@code null}
   * @return a fresh session on success, or why it was refused
   */
  ChangePasswordOutcome changePassword(Actor actor, ChangePasswordCommand command);
}
