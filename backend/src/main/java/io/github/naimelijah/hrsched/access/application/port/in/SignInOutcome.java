package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Objects;
import java.util.Optional;

/**
 * What came of an attempt to sign in.
 *
 * <p><strong>A returned value rather than an exception, and that is load-bearing.</strong> Every
 * use case here runs in one transaction that rolls back on any unchecked exception (ADR-023). A
 * refusal thrown from sign-in would therefore roll back the failed attempt it had just counted, so
 * an account could be guessed at for ever and never lock. Refusals that have to leave a trace are
 * returned; ADR-011 records the departure.
 *
 * <p>Sealed, so the two outcomes are enumerated in one place and a caller cannot forget one.
 */
public sealed interface SignInOutcome {

  /**
   * The credentials were accepted and a session began.
   *
   * @param session the tokens and the identity they belong to
   */
  record SignedIn(IssuedSession session) implements SignInOutcome {

    /**
     * @throws NullPointerException if {@code session} is null
     */
    public SignedIn {
      Objects.requireNonNull(session, "session must not be null");
    }
  }

  /**
   * The credentials were not accepted.
   *
   * <p>The reason is for the log and the metric. Every refusal looks identical to whoever submitted
   * the credentials, because which of these it was is exactly what an attacker is trying to learn.
   *
   * @param account whose account it was, empty when no account holds that name
   * @param reason why it was refused, for recording rather than for answering
   */
  record Refused(Optional<UserId> account, SignInRefusal reason) implements SignInOutcome {

    /**
     * @throws NullPointerException if {@code account} or {@code reason} is null
     */
    public Refused {
      Objects.requireNonNull(account, "account must not be null");
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}
