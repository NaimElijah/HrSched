package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.access.domain.model.SessionRevocation;
import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Instant;
import java.util.Optional;

/**
 * Driven port: storing sign-in sessions and the refresh tokens that renew them.
 *
 * <p>Deliberately not scoped by organisation, unlike every other repository here. A session belongs
 * to an account rather than to a tenant, and the operator's sessions belong to no tenant at all;
 * scoping would be a filter that could not be written for half the rows. What keeps it safe is that
 * a session is only ever reached by an identifier that came out of a verified token, or by the
 * account it belongs to.
 */
public interface SessionRepository {

  /**
   * Opens a session and issues its first refresh token, as one act.
   *
   * @param session the session being opened, never {@code null}
   * @param refreshDigest the digest of its first refresh token, never {@code null}
   * @param refreshExpiresAt when that token stops being accepted, never {@code null}
   */
  void start(SignInSession session, byte[] refreshDigest, Instant refreshExpiresAt);

  /**
   * Finds a refresh token by its digest, together with the session it renews.
   *
   * <p>By digest and never by value: the value is not stored, so there is nothing else to look one
   * up by. A caller digests what was presented and asks for that.
   *
   * @param refreshDigest the digest of the token presented, never {@code null}
   * @return the token and its session, or empty if no such token was ever issued
   */
  Optional<StoredRefreshToken> findRefreshToken(byte[] refreshDigest);

  /**
   * Reads an account together with one of its sessions, in one query.
   *
   * <p>The read on the hot path: every authenticated request asks it, because a token's signature
   * says only that this system issued it and says nothing about whether the account is still
   * enabled or the session still live.
   *
   * <p><strong>Not scoped by organisation,</strong> and cannot be: this is the read that
   * <em>discovers</em> which organisation the caller belongs to, and the platform operator belongs
   * to none. The account identifier comes from a token this system signed, so there is nothing a
   * caller could substitute.
   *
   * @param account the account a token names, never {@code null}
   * @param session the session it names, never {@code null}
   * @return the pair, or empty if there is no such account, or the session is not that account's
   */
  Optional<AuthenticatedSession> findAuthenticatedSession(UserId account, SignInSessionId session);

  /**
   * Marks a refresh token as exchanged, but only if it has not been exchanged already.
   *
   * <p>Conditional on purpose, and it is the whole of the race protection. Two requests arriving
   * with the same token would otherwise both read it as current and both rotate it; here exactly
   * one changes the row, and the other is told it did not - which is indistinguishable from, and
   * treated as, somebody presenting a token that had already been used.
   *
   * @param refreshDigest the digest of the token being exchanged, never {@code null}
   * @param now when it was exchanged, never {@code null}
   * @return whether this call was the one that marked it; false means it was already replaced
   */
  boolean markReplaced(byte[] refreshDigest, Instant now);

  /**
   * Issues the successor to a token just exchanged.
   *
   * @param session the session it renews, never {@code null}
   * @param refreshDigest the digest of the new token, never {@code null}
   * @param issuedAt when it was issued, never {@code null}
   * @param expiresAt when it stops being accepted, never {@code null}
   */
  void issueSuccessor(
      SignInSessionId session, byte[] refreshDigest, Instant issuedAt, Instant expiresAt);

  /**
   * Ends a session early.
   *
   * <p>Records why as well as when, because the reasons are not interchangeable: a session ended by
   * reuse detection is evidence of a stolen token, and one ended by signing out is somebody
   * leaving. Revoking a session that is already revoked changes nothing, so the first reason
   * stands.
   *
   * @param session which session to end, never {@code null}
   * @param reason why it is ending, never {@code null}
   * @param now when it ended, never {@code null}
   */
  void revoke(SignInSessionId session, SessionRevocation reason, Instant now);

  /**
   * Ends every live session of one account.
   *
   * <p>For the acts that invalidate everything opened under the old state: a password changed, an
   * account disabled, a password reset. Leaving one session alive after any of those would leave
   * the door somebody was being shut out of standing open.
   *
   * @param account whose sessions to end, never {@code null}
   * @param reason why they are ending, never {@code null}
   * @param now when they ended, never {@code null}
   */
  void revokeEverySessionOf(UserId account, SessionRevocation reason, Instant now);
}
