package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One span of being signed in: who, from when, until when, and whether it was ended early.
 *
 * <p>An entity — two sessions of the same account over the same hours are still two sessions, so
 * equality is by {@link SignInSessionId} alone. Somebody signed in from a laptop and a phone has
 * two, and ending one must not end the other.
 *
 * <p>This is what revocation acts on, and it is why access tokens are not stored anywhere. Every
 * token names its session, so a session that is no longer live makes every token issued for it
 * useless on its next use — without this system ever having held a copy of one.
 *
 * <p>Immutable. Revoking returns a new instance, so a session can never be observed half-ended.
 */
public final class SignInSession {

  private final SignInSessionId id;
  private final UserId account;
  private final Instant startedAt;
  private final Instant expiresAt;
  private final Optional<SessionRevocation> revocation;
  private final Optional<Instant> revokedAt;

  /**
   * Opens a session that runs until its expiry unless something ends it sooner.
   *
   * @param id the session's identity, never {@code null}
   * @param account whose session it is, never {@code null}
   * @param startedAt when it began, never {@code null}
   * @param expiresAt when it ends of its own accord, strictly after it began
   * @throws IllegalArgumentException if it would end at or before it began
   */
  public SignInSession(SignInSessionId id, UserId account, Instant startedAt, Instant expiresAt) {
    this(id, account, startedAt, expiresAt, Optional.empty(), Optional.empty());
  }

  /**
   * Rebuilds a stored session, including a revocation the transitions cannot restore.
   *
   * @param id the session's identity, never {@code null}
   * @param account whose session it is, never {@code null}
   * @param startedAt when it began, never {@code null}
   * @param expiresAt when it ends of its own accord, strictly after it began
   * @param revocation why it was ended early, empty if it was not
   * @param revokedAt when it was ended early, present exactly when {@code revocation} is
   * @throws IllegalArgumentException if it would end at or before it began, or if only one half of
   *     the revocation is present
   */
  public SignInSession(
      SignInSessionId id,
      UserId account,
      Instant startedAt,
      Instant expiresAt,
      Optional<SessionRevocation> revocation,
      Optional<Instant> revokedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.account = Objects.requireNonNull(account, "account must not be null");
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    this.revocation = Objects.requireNonNull(revocation, "revocation must not be null");
    this.revokedAt = Objects.requireNonNull(revokedAt, "revokedAt must not be null");
    if (!expiresAt.isAfter(startedAt)) {
      throw new IllegalArgumentException("A session must end after it starts");
    }
    if (revocation.isPresent() != revokedAt.isPresent()) {
      throw new IllegalArgumentException("A revoked session must say both why and when");
    }
  }

  /**
   * @param now the instant being asked about, never {@code null}
   * @return whether the session is still usable then: not revoked, and not yet expired
   */
  public boolean isLiveAt(Instant now) {
    Objects.requireNonNull(now, "now must not be null");
    return revocation.isEmpty() && now.isBefore(expiresAt);
  }

  /**
   * @param reason why it is ending, never {@code null}
   * @param now when it ended, never {@code null}
   * @return the session, ended; revoking one already revoked keeps the first reason, because the
   *     first thing that ended it is the thing that ended it
   */
  public SignInSession revoked(SessionRevocation reason, Instant now) {
    Objects.requireNonNull(reason, "reason must not be null");
    Objects.requireNonNull(now, "now must not be null");
    if (revocation.isPresent()) {
      return this;
    }
    return new SignInSession(
        id, account, startedAt, expiresAt, Optional.of(reason), Optional.of(now));
  }

  public SignInSessionId id() {
    return id;
  }

  public UserId account() {
    return account;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Optional<SessionRevocation> revocation() {
    return revocation;
  }

  public Optional<Instant> revokedAt() {
    return revokedAt;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SignInSession session && id.equals(session.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "SignInSession[id=%s]".formatted(id);
  }
}
