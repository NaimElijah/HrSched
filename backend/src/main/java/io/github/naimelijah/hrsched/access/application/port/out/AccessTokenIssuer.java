package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Driven port: minting the token a client presents on every subsequent request.
 *
 * <p>Says nothing about the format. That the token is a signed JWT, which algorithm signs it and
 * which claims it carries are the adapter's business entirely (ADR-011); everything inward of this
 * port knows only that a client can be handed something that proves, for a while, which account and
 * which session it belongs to.
 *
 * <p>The token names the session as well as the account, so that ending a session ends every token
 * issued for it. Without that, signing out would leave a working credential behind until it
 * happened to expire.
 */
public interface AccessTokenIssuer {

  /**
   * @param account whose token this is, never {@code null}
   * @param session the sign-in session it belongs to, never {@code null}
   * @return the signed token and the instant it expires
   */
  IssuedAccessToken issue(UserId account, SignInSessionId session);
}
