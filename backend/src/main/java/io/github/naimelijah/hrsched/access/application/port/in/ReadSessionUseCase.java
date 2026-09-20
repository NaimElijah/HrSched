package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import java.util.Optional;

/**
 * Driving port: telling a caller who they are signed in as.
 *
 * <p>Strictly self-service. It takes the acting identity and no parameter naming anybody, so there
 * is nothing to pass that would let one person read another's account.
 */
public interface ReadSessionUseCase {

  /**
   * @param actor whoever is asking, never {@code null}
   * @return their own account as this context publishes it, or empty if the account has gone since
   *     the request was authenticated
   */
  Optional<PublishedSession> findSession(Actor actor);
}
