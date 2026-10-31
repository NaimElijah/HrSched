package io.github.naimelijah.hrsched.access.application.port.in;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What came of an attempt to renew a session.
 *
 * <p>Returned rather than thrown, and here the reason is sharper than it is for sign-in: detecting
 * a reused token <em>revokes the whole session</em>, and that revocation has to be committed even
 * though the request itself is refused. A thrown refusal would roll it back (ADR-023), so the one
 * response to a token having been stolen would be undone by the act of refusing it.
 *
 * <p>Sealed, so both outcomes are enumerated in one place.
 */
public sealed interface RefreshOutcome {

  /**
   * The token was good, and has been exchanged for a successor.
   *
   * @param session the new tokens and the identity they belong to
   */
  record Renewed(IssuedSession session) implements RefreshOutcome {

    /**
     * @throws NullPointerException if {@code session} is null
     */
    public Renewed {
      Objects.requireNonNull(session, "session must not be null");
    }
  }

  /**
   * The token was not accepted, and the client is told nothing about why.
   *
   * <p>The session identifier travels with the refusal for one reason only: a reuse is worth
   * alerting on, and an alert nobody can trace to a session is not much of an alert. It is a {@code
   * UUID} rather than a domain identity because everything this port publishes is.
   *
   * @param session which session it was about, empty when the token named none this system knows
   * @param reason why it was refused, for recording rather than for answering
   */
  record Refused(Optional<UUID> session, RefreshRefusal reason) implements RefreshOutcome {

    /**
     * @throws NullPointerException if {@code session} or {@code reason} is null
     */
    public Refused {
      Objects.requireNonNull(session, "session must not be null");
      Objects.requireNonNull(reason, "reason must not be null");
    }

    /**
     * @param reason why the renewal was refused
     * @return a refusal about no session this system recognises
     */
    public static Refused of(RefreshRefusal reason) {
      return new Refused(Optional.empty(), reason);
    }
  }
}
