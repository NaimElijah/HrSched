package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Optional;

/**
 * Driving port: turning what a verified token claims into who is actually acting.
 *
 * <p>Asked on <strong>every authenticated request</strong>, which is the point of it. A token's
 * signature proves only that this system issued it; whether the account still exists, is still
 * enabled, still holds the role it did, and whether its session is still live are facts about now,
 * and they live in the database rather than in a token minted minutes ago.
 *
 * <p>That is what makes disabling an account, changing a role, and signing out take effect on the
 * next request instead of whenever the token happens to expire. It costs one read per request, and
 * ADR-011 records that as a deliberate trade.
 */
public interface AuthenticatedActorsUseCase {

  /**
   * @param account the account a token names, never {@code null}
   * @param session the session it names, never {@code null}
   * @return who is acting, or empty when the token should no longer be believed - no such account,
   *     a session belonging to somebody else, a disabled account, or a session revoked or past its
   *     end. The caller answers 401 and is told nothing about which
   */
  Optional<AuthenticatedActor> findActor(UserId account, SignInSessionId session);
}
