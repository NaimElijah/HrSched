package io.github.naimelijah.hrsched.access.application.service;

import io.github.naimelijah.hrsched.access.application.port.in.AccountKind;
import io.github.naimelijah.hrsched.access.application.port.in.AuthenticatedActor;
import io.github.naimelijah.hrsched.access.application.port.in.AuthenticatedActorsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.ChangePasswordCommand;
import io.github.naimelijah.hrsched.access.application.port.in.ChangePasswordOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.ChangePasswordUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.IssuedSession;
import io.github.naimelijah.hrsched.access.application.port.in.PublishedSession;
import io.github.naimelijah.hrsched.access.application.port.in.ReadSessionUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.RefreshOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.RefreshRefusal;
import io.github.naimelijah.hrsched.access.application.port.in.SessionsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.SignInCommand;
import io.github.naimelijah.hrsched.access.application.port.in.SignInOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.SignInRefusal;
import io.github.naimelijah.hrsched.access.application.port.in.SignInUseCase;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AccessTokenIssuer;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.access.application.port.out.IssuedAccessToken;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.application.port.out.RefreshTokenSecret;
import io.github.naimelijah.hrsched.access.application.port.out.SessionRepository;
import io.github.naimelijah.hrsched.access.application.port.out.SessionSecrets;
import io.github.naimelijah.hrsched.access.application.port.out.StoredRefreshToken;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.InvalidSignInNameException;
import io.github.naimelijah.hrsched.access.domain.model.LockoutPolicy;
import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.PasswordRejection;
import io.github.naimelijah.hrsched.access.domain.model.SessionPolicy;
import io.github.naimelijah.hrsched.access.domain.model.SessionRevocation;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates signing in.
 *
 * <p>Three properties hold this together, and each of them is a way somebody could otherwise learn
 * something they should not.
 *
 * <p><strong>Every refusal is the same refusal.</strong> An unknown name, a wrong password, a
 * disabled account and a locked one all return a {@code Refused}, and the web adapter answers all
 * four identically. Which one it was is recorded, never answered: telling them apart would say
 * whether a name is in use, whether an account has been switched off, and whether somebody has been
 * guessing at it.
 *
 * <p><strong>Exactly one password comparison always runs.</strong> Including for a name nobody
 * holds, which is compared against a dummy hash computed once at construction. Skipping the work
 * when there is no account would make an unknown name measurably faster to refuse than a known one,
 * and that timing difference is a way to enumerate who has an account here.
 *
 * <p><strong>Refusals are returned, not thrown.</strong> The use case runs in one transaction that
 * rolls back on any unchecked exception (ADR-023), so throwing would undo the failed attempt this
 * had just counted and no account would ever lock. ADR-011 records the departure.
 */
public class SessionService
    implements SignInUseCase,
        SessionsUseCase,
        AuthenticatedActorsUseCase,
        ReadSessionUseCase,
        ChangePasswordUseCase {

  /** Bytes of randomness behind the dummy password, which exists only to be compared against. */
  private static final int DUMMY_PASSWORD_BYTES = 32;

  private final AccountRepository accounts;
  private final SessionRepository sessions;
  private final PasswordHasher passwords;
  private final AccessTokenIssuer tokens;
  private final SessionSecrets secrets;
  private final AuditTrail audit;
  private final Clock clock;
  private final LockoutPolicy lockout;
  private final SessionPolicy sessionPolicy;
  private final PasswordPolicy passwordPolicy;
  private final PasswordHash dummyHash;

  public SessionService(
      AccountRepository accounts,
      SessionRepository sessions,
      PasswordHasher passwords,
      AccessTokenIssuer tokens,
      SessionSecrets secrets,
      AuditTrail audit,
      Clock clock,
      LockoutPolicy lockout,
      SessionPolicy sessionPolicy,
      PasswordPolicy passwordPolicy) {
    this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
    this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    this.passwords = Objects.requireNonNull(passwords, "passwords must not be null");
    this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
    this.secrets = Objects.requireNonNull(secrets, "secrets must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.lockout = Objects.requireNonNull(lockout, "lockout must not be null");
    this.sessionPolicy = Objects.requireNonNull(sessionPolicy, "sessionPolicy must not be null");
    this.passwordPolicy = Objects.requireNonNull(passwordPolicy, "passwordPolicy must not be null");
    this.dummyHash = passwords.hash(randomPassword());
  }

  @Override
  public SignInOutcome signIn(SignInCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    Instant now = clock.instant();
    Optional<Account> found = accountNamed(command.signInName());

    // Always exactly one comparison, whatever was found, so that how long a refusal takes says
    // nothing about whether the name exists.
    PasswordHash against = found.map(Account::passwordHash).orElse(dummyHash);
    boolean passwordMatches = passwords.matches(command.password(), against);

    if (found.isEmpty()) {
      return new SignInOutcome.Refused(Optional.empty(), SignInRefusal.UNKNOWN_ACCOUNT);
    }
    Account account = found.get();
    if (account.isLockedAt(now)) {
      // Nothing is counted while locked: a lock that extended itself on every attempt would let
      // anybody keep an account shut indefinitely just by trying.
      return new SignInOutcome.Refused(Optional.of(account.id()), SignInRefusal.LOCKED);
    }
    if (!account.active()) {
      return new SignInOutcome.Refused(Optional.of(account.id()), SignInRefusal.DISABLED);
    }
    if (!passwordMatches) {
      return refuseAndCount(account, now);
    }
    return signedIn(account, now);
  }

  /**
   * Exchanges a refresh token for a new pair.
   *
   * <p>Reading the order of the checks matters. Reuse is decided <em>before</em> anything about
   * whether the session is otherwise usable, because a token presented twice is evidence about a
   * stolen credential regardless of what else is wrong, and it is the one case that changes state
   * on the way out.
   */
  @Override
  public RefreshOutcome refresh(String refreshToken) {
    Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    Instant now = clock.instant();
    Optional<StoredRefreshToken> found = sessions.findRefreshToken(secrets.digestOf(refreshToken));
    if (found.isEmpty()) {
      return RefreshOutcome.Refused.of(RefreshRefusal.UNKNOWN_TOKEN);
    }
    StoredRefreshToken presented = found.get();
    SignInSession session = presented.session();
    if (presented.isReplaced()) {
      return reuseDetected(session, now);
    }
    if (!session.isLiveAt(now)) {
      return refusedAbout(session, RefreshRefusal.SESSION_ENDED);
    }
    if (presented.hasExpiredAt(now)) {
      return refusedAbout(session, RefreshRefusal.EXPIRED);
    }
    Optional<Account> owner = accounts.findForAuthentication(session.account());
    if (owner.isEmpty() || !owner.get().active()) {
      return refusedAbout(session, RefreshRefusal.ACCOUNT_DISABLED);
    }
    // The conditional update is what decides the race: two requests holding one token both reach
    // here, and the one that does not change a row is presenting a token somebody else just spent.
    if (!sessions.markReplaced(secrets.digestOf(refreshToken), now)) {
      return reuseDetected(session, now);
    }
    RefreshTokenSecret successor = secrets.generate();
    Instant expiresAt = earlier(now.plus(sessionPolicy.idleLifetime()), session.expiresAt());
    sessions.issueSuccessor(session.id(), successor.digest(), now, expiresAt);
    IssuedAccessToken accessToken = tokens.issue(session.account(), session.id());
    return new RefreshOutcome.Renewed(
        new IssuedSession(
            accessToken.value(),
            accessToken.expiresAt(),
            successor.value(),
            expiresAt,
            publish(owner.get())));
  }

  /**
   * The read every authenticated request makes. Nothing here trusts the token beyond the two
   * identifiers it names: whether the account is usable and the session live are read fresh, which
   * is what makes disabling, demoting and signing out take effect on the next request.
   *
   * <p>A session belonging to another account simply is not found, because the query asks for both
   * at once — a token pairing one account's subject with another's session matches no row.
   *
   * <p><strong>A lockout is deliberately not checked here.</strong> An account is locked because
   * somebody is guessing at its password, and the person doing the guessing is not the person
   * holding this session; ending it would turn an attack on somebody into an outage for them, and
   * hand any attacker a way to sign a colleague out by mistyping their password ten times. The lock
   * stops a <em>sign-in</em>, which is where the guessing happens, and nothing else. Adding the
   * filter here would look like tightening and would be a regression.
   */
  @Override
  public Optional<AuthenticatedActor> findActor(UserId account, SignInSessionId session) {
    Objects.requireNonNull(account, "account must not be null");
    Objects.requireNonNull(session, "session must not be null");
    Instant now = clock.instant();
    return sessions
        .findAuthenticatedSession(account, session)
        .filter(found -> found.account().active())
        .filter(found -> found.session().isLiveAt(now))
        .map(
            found ->
                new AuthenticatedActor(
                    found.account().actor(), found.account().passwordChangeRequired()));
  }

  @Override
  public Optional<PublishedSession> findSession(Actor actor) {
    Objects.requireNonNull(actor, "actor must not be null");
    return accounts.findForAuthentication(actor.id()).map(SessionService::publish);
  }

  /**
   * Changes the caller's own password, and ends every session it had.
   *
   * <p>The current password is checked even though the caller is authenticated, and a wrong one is
   * counted as a failed attempt: without that, this endpoint would be a way to guess at a password
   * with no lockout behind it. That count is why the refusal is returned rather than thrown.
   */
  @Override
  public ChangePasswordOutcome changePassword(Actor actor, ChangePasswordCommand command) {
    Objects.requireNonNull(actor, "actor must not be null");
    Objects.requireNonNull(command, "command must not be null");
    Instant now = clock.instant();
    // Authenticated moments ago, so an account that is not there is a fault rather than a refusal,
    // and fails loudly instead of being reported as a wrong password. Held, because the change
    // stores the account whole: a disable or a reset committed meanwhile would otherwise be stored
    // over (ADR-041).
    Account account =
        accounts
            .findForUpdate(organisationOf(actor), actor.id())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "The authenticated account no longer exists: " + actor.id().value()));
    if (!passwords.matches(command.currentPassword(), account.passwordHash())) {
      Account counted = account.recordFailedSignIn(now, lockout);
      accounts.save(counted);
      if (counted.isLockedAt(now)) {
        record(counted, AccessChange.ACCOUNT_LOCKED, counted.id().value());
      }
      return new ChangePasswordOutcome.CurrentPasswordRejected();
    }
    Optional<PasswordRejection> broken = passwordPolicy.assess(command.newPassword());
    if (broken.isPresent()) {
      return new ChangePasswordOutcome.NewPasswordRejected(broken.get());
    }
    if (passwords.matches(command.newPassword(), account.passwordHash())) {
      return new ChangePasswordOutcome.NewPasswordRejected(PasswordRejection.SAME_AS_CURRENT);
    }
    Account changed =
        account.withPassword(passwords.hash(command.newPassword()), false).recordSuccessfulSignIn();
    accounts.save(changed);
    // Every session, including the one that asked: a password change has to end anything opened
    // under the old one, and the caller is handed a new session rather than being signed out of
    // their own password change.
    sessions.revokeEverySessionOf(changed.id(), SessionRevocation.PASSWORD_CHANGED, now);
    record(changed, AccessChange.PASSWORD_CHANGED, changed.id().value());
    return new ChangePasswordOutcome.Changed(beginSession(changed, now, AccessChange.SIGNED_IN));
  }

  @Override
  public void signOut(String refreshToken) {
    Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    Instant now = clock.instant();
    sessions
        .findRefreshToken(secrets.digestOf(refreshToken))
        .map(StoredRefreshToken::session)
        .filter(session -> session.isLiveAt(now))
        .ifPresent(
            session -> {
              sessions.revoke(session.id(), SessionRevocation.SIGNED_OUT, now);
              recordAbout(session, AccessChange.SIGNED_OUT);
            });
  }

  /**
   * Ends the whole session, not merely this request. Two parties hold a token that works exactly
   * once, and there is no way to tell which of them is its owner, so the only safe move is to stop
   * believing both.
   */
  private RefreshOutcome reuseDetected(SignInSession session, Instant now) {
    sessions.revoke(session.id(), SessionRevocation.REFRESH_REUSED, now);
    recordAbout(session, AccessChange.REFRESH_TOKEN_REUSED);
    return new RefreshOutcome.Refused(Optional.of(session.id().value()), RefreshRefusal.REUSED);
  }

  private static RefreshOutcome refusedAbout(SignInSession session, RefreshRefusal reason) {
    return new RefreshOutcome.Refused(Optional.of(session.id().value()), reason);
  }

  /**
   * Records a change about a session, looking its account up for the organisation to file under.
   */
  private void recordAbout(SignInSession session, AccessChange change) {
    Optional<Account> owner = accounts.findForAuthentication(session.account());
    audit.record(
        new AuditedChange(
            owner.flatMap(Account::organisation), session.account(), change, session.id().value()));
  }

  private SignInOutcome refuseAndCount(Account account, Instant now) {
    Account counted = account.recordFailedSignIn(now, lockout);
    accounts.save(counted);
    if (counted.isLockedAt(now)) {
      record(counted, AccessChange.ACCOUNT_LOCKED, counted.id().value());
    }
    return new SignInOutcome.Refused(Optional.of(account.id()), SignInRefusal.WRONG_PASSWORD);
  }

  private SignInOutcome signedIn(Account account, Instant now) {
    if (account.failedSignIns() > 0 || account.lockedUntil().isPresent()) {
      accounts.save(account.recordSuccessfulSignIn());
    }
    return new SignInOutcome.SignedIn(beginSession(account, now, AccessChange.SIGNED_IN));
  }

  /**
   * Opens a session and hands back everything a client needs to use it.
   *
   * @param account whose session it is
   * @param now when it begins
   * @param change what is recorded about it, since a session begun by a password change is not a
   *     sign-in and should not be counted as one
   */
  private IssuedSession beginSession(Account account, Instant now, AccessChange change) {
    SignInSession session =
        new SignInSession(
            SignInSessionId.newId(), account.id(), now, now.plus(sessionPolicy.absoluteLifetime()));
    // A refresh token never outlives its session: renewing extends nothing, so a stolen one cannot
    // be used to keep a session alive past the end it was given when it began.
    Instant refreshExpiresAt = earlier(now.plus(sessionPolicy.idleLifetime()), session.expiresAt());
    RefreshTokenSecret refresh = secrets.generate();
    sessions.start(session, refresh.digest(), refreshExpiresAt);
    IssuedAccessToken accessToken = tokens.issue(account.id(), session.id());
    record(account, change, session.id().value());
    return new IssuedSession(
        accessToken.value(),
        accessToken.expiresAt(),
        refresh.value(),
        refreshExpiresAt,
        publish(account));
  }

  /**
   * A name that cannot be a sign-in name is treated exactly as one nobody holds. Refusing it
   * differently would answer, for free, which shapes of name this system uses.
   */
  private Optional<Account> accountNamed(String submitted) {
    try {
      return accounts.findBySignInName(new SignInName(submitted));
    } catch (InvalidSignInNameException notAName) {
      return Optional.empty();
    }
  }

  /** Where the actor's own account is kept: in their organisation, or in none for the operator. */
  private static Optional<OrganisationId> organisationOf(Actor actor) {
    return switch (actor) {
      case TenantActor tenant -> Optional.of(tenant.organisation());
      case Operator operator -> Optional.empty();
    };
  }

  private void record(Account account, AccessChange change, java.util.UUID subject) {
    audit.record(new AuditedChange(account.organisation(), account.id(), change, subject));
  }

  private static PublishedSession publish(Account account) {
    boolean tenant = account.membership() instanceof TenantMembership;
    return new PublishedSession(
        account.id().value(),
        tenant ? AccountKind.TENANT : AccountKind.OPERATOR,
        account.organisation().map(OrganisationId::value),
        tenantMembership(account).map(TenantMembership::role),
        tenantMembership(account).flatMap(TenantMembership::employee).map(EmployeeId::value),
        account.signInName().value(),
        account.passwordChangeRequired());
  }

  private static Optional<TenantMembership> tenantMembership(Account account) {
    return account.membership() instanceof TenantMembership membership
        ? Optional.of(membership)
        : Optional.empty();
  }

  private static Instant earlier(Instant one, Instant other) {
    return one.isBefore(other) ? one : other;
  }

  private static String randomPassword() {
    byte[] bytes = new byte[DUMMY_PASSWORD_BYTES];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
