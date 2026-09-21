package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import java.util.Objects;

/**
 * An account and one of its sessions, read together.
 *
 * <p>Together because every authenticated request asks about both at once - is this account still
 * usable, and is this session still live - and two reads would be two moments at which the answers
 * could differ. It is also the one read on the hot path, so it is one query.
 *
 * @param account whose session it is
 * @param session the session a token named
 */
public record AuthenticatedSession(Account account, SignInSession session) {

  public AuthenticatedSession {
    Objects.requireNonNull(account, "account must not be null");
    Objects.requireNonNull(session, "session must not be null");
  }
}
