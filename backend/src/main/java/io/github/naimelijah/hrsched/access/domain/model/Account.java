package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Something that can sign in: a credential, what it is a member of, and whether it may be used.
 *
 * <p>An entity — two accounts holding the same role in the same organisation are still two people,
 * so equality is by {@link UserId} alone.
 *
 * <p>Immutable. Every change returns a new instance, so an account can never be observed
 * half-updated and the invariants are checked exactly once, in one place. The lockout arithmetic in
 * particular is easier to trust when nothing mutates underneath it.
 *
 * <p>The account is the source the acting identity is derived from, through {@link #actor()}, which
 * is why {@link Membership} is structural rather than a role field plus a nullable organisation: an
 * operator is not a tenant member with columns left blank, and the type says so.
 *
 * <p>Holds no password, only an encoded {@link PasswordHash}, and can neither check nor produce
 * one. Verifying a password belongs to the hasher adapter, because only it knows the encoding.
 *
 * <p>An account holding the Employee role is always linked to an employee (R34). No transition
 * makes one linked to nobody, and no constructor makes one usable. The one such account that can
 * exist was stored before the rule: migration {@code V9} disabled it, the rebuilding constructor
 * still loads it so that it can be linked, and it can be restored only once it is.
 */
public final class Account {

  private final UserId id;
  private final SignInName signInName;
  private final PasswordHash passwordHash;
  private final Membership membership;
  private final boolean active;
  private final boolean passwordChangeRequired;
  private final int failedSignIns;
  private final Optional<Instant> lockedUntil;
  private final Instant createdAt;

  /**
   * A newly provisioned account: active, with no failures behind it and no lock.
   *
   * @param id the account's identity, never {@code null}
   * @param signInName the name it signs in with, never {@code null}
   * @param passwordHash its encoded password, never {@code null}
   * @param membership what it is a member of, never {@code null}
   * @param passwordChangeRequired whether the password must be changed before anything else; true
   *     for an account created with a temporary password somebody else chose
   * @param createdAt when it was provisioned, never {@code null}
   * @throws NullPointerException if any reference argument is null
   * @throws UnlinkedEmployeeAccountException if it holds the Employee role linked to nobody (R34)
   */
  public Account(
      UserId id,
      SignInName signInName,
      PasswordHash passwordHash,
      Membership membership,
      boolean passwordChangeRequired,
      Instant createdAt) {
    this(
        id,
        signInName,
        passwordHash,
        membership,
        true,
        passwordChangeRequired,
        0,
        Optional.empty(),
        createdAt);
  }

  /**
   * Rebuilds a stored account, including the sign-in history no behaviour here can restore.
   *
   * <p>For the persistence adapter. Everything else reaches the same states through the methods
   * below, so that the transitions stay the only way an account changes.
   *
   * @param id the account's identity, never {@code null}
   * @param signInName the name it signs in with, never {@code null}
   * @param passwordHash its encoded password, never {@code null}
   * @param membership what it is a member of, never {@code null}
   * @param active whether it may be used at all
   * @param passwordChangeRequired whether the password must be changed before anything else
   * @param failedSignIns consecutive failures counted since the last success or unlock, never
   *     negative
   * @param lockedUntil when the current lock ends, empty if there is none
   * @param createdAt when it was provisioned, never {@code null}
   * @throws NullPointerException if any reference argument is null
   * @throws IllegalArgumentException if {@code failedSignIns} is negative
   * @throws UnlinkedEmployeeAccountException if it is active and holds the Employee role linked to
   *     nobody (R34); a disabled one is what {@code V9} left of an account stored before the rule
   */
  public Account(
      UserId id,
      SignInName signInName,
      PasswordHash passwordHash,
      Membership membership,
      boolean active,
      boolean passwordChangeRequired,
      int failedSignIns,
      Optional<Instant> lockedUntil,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.signInName = Objects.requireNonNull(signInName, "signInName must not be null");
    this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
    this.membership = Objects.requireNonNull(membership, "membership must not be null");
    this.lockedUntil = Objects.requireNonNull(lockedUntil, "lockedUntil must not be null");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    if (failedSignIns < 0) {
      throw new IllegalArgumentException(
          "An account cannot have failed a negative number of times");
    }
    if (active && isAnUnlinkedEmployee(membership)) {
      throw new UnlinkedEmployeeAccountException(id);
    }
    this.active = active;
    this.passwordChangeRequired = passwordChangeRequired;
    this.failedSignIns = failedSignIns;
  }

  /**
   * @return the identity this account acts as once it has signed in
   */
  public Actor actor() {
    return switch (membership) {
      case TenantMembership tenant ->
          new TenantActor(id, tenant.organisation(), tenant.role(), tenant.employee());
      case OperatorMembership operator -> new Operator(id);
    };
  }

  /**
   * @return the organisation this account belongs to, empty for the platform operator
   */
  public Optional<OrganisationId> organisation() {
    return switch (membership) {
      case TenantMembership tenant -> Optional.of(tenant.organisation());
      case OperatorMembership operator -> Optional.empty();
    };
  }

  /**
   * @param now the instant being asked about, never {@code null}
   * @return whether a lock is still running at that instant; a lock ends at the instant it expires
   */
  public boolean isLockedAt(Instant now) {
    Objects.requireNonNull(now, "now must not be null");
    return lockedUntil.filter(now::isBefore).isPresent();
  }

  /**
   * Counts one failed sign-in, locking the account once the policy's threshold is reached.
   *
   * <p>A lock that has run out leaves nothing behind: the next failure starts a fresh run, so a
   * second lock costs an attacker exactly as many guesses as the first. A failure arriving while a
   * lock is still running restarts that lock's clock.
   *
   * @param now when the attempt failed, never {@code null}
   * @param policy how many failures lock the account and for how long, never {@code null}
   * @return the account with this failure counted
   */
  public Account recordFailedSignIn(Instant now, LockoutPolicy policy) {
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(policy, "policy must not be null");
    boolean lockHasRunOut = lockedUntil.isPresent() && !isLockedAt(now);
    int failures = lockHasRunOut ? 1 : failedSignIns + 1;
    Optional<Instant> lock =
        failures >= policy.threshold()
            ? Optional.of(now.plus(policy.duration()))
            : Optional.empty();
    return new Account(
        id,
        signInName,
        passwordHash,
        membership,
        active,
        passwordChangeRequired,
        failures,
        lock,
        createdAt);
  }

  /**
   * @return the account with its failure count and any lock cleared
   */
  public Account recordSuccessfulSignIn() {
    return new Account(
        id,
        signInName,
        passwordHash,
        membership,
        active,
        passwordChangeRequired,
        0,
        Optional.empty(),
        createdAt);
  }

  /**
   * @return the account, unusable until enabled again; disabling one already disabled is not an
   *     error
   */
  public Account disabled() {
    return new Account(
        id,
        signInName,
        passwordHash,
        membership,
        false,
        passwordChangeRequired,
        failedSignIns,
        lockedUntil,
        createdAt);
  }

  /**
   * @return the account, usable again; enabling one already enabled is not an error
   * @throws UnlinkedEmployeeAccountException if it holds the Employee role linked to nobody, which
   *     must be linked before it may be used (R34)
   */
  public Account enabled() {
    return new Account(
        id,
        signInName,
        passwordHash,
        membership,
        true,
        passwordChangeRequired,
        failedSignIns,
        lockedUntil,
        createdAt);
  }

  /**
   * Changes what the account is within its organisation: the role and the employee link together.
   *
   * <p>One transition rather than one for each, because R34 is a rule about the pair: an account
   * holding the Employee role is always linked to an employee. Changed one at a time, an unlinked
   * Scheduler becoming a linked Employee would be an unlinked Employee in between.
   *
   * @param role the role this account is to hold in its organisation, never {@code null}
   * @param employee the personnel record this account is, empty for nobody's, never {@code null}
   * @return the account in that role, linked to that employee
   * @throws OperatorBelongsToNoOrganisationException if this is the platform operator, which holds
   *     no role and is nobody's employee
   * @throws UnlinkedEmployeeAccountException if the role is Employee and the link empty, whether or
   *     not the account is active: no change makes an Employee linked to nobody (R34)
   */
  public Account withMembership(Role role, Optional<EmployeeId> employee) {
    Objects.requireNonNull(role, "role must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
    TenantMembership tenant = requireTenantMembership();
    TenantMembership changed = new TenantMembership(tenant.organisation(), role, employee);
    if (isAnUnlinkedEmployee(changed)) {
      throw new UnlinkedEmployeeAccountException(id);
    }
    return replacingMembership(changed);
  }

  /**
   * Clears any lock and the failures behind it, without anybody having signed in.
   *
   * <p>Separate from {@link #recordSuccessfulSignIn()} although the state it leaves is the same,
   * because they are different events: one is the owner proving they know the password, the other
   * is an administrator deciding they should stop being locked out. Naming them apart is what keeps
   * a reset from reading like a sign-in nobody made.
   *
   * @return the account with its failure count and any lock cleared
   */
  public Account unlocked() {
    return new Account(
        id,
        signInName,
        passwordHash,
        membership,
        active,
        passwordChangeRequired,
        0,
        Optional.empty(),
        createdAt);
  }

  /**
   * @param chosen the new encoded password, never {@code null}
   * @param changeRequired whether it must be changed again before anything else; true when somebody
   *     other than its owner chose it
   * @return the account with that password
   */
  public Account withPassword(PasswordHash chosen, boolean changeRequired) {
    Objects.requireNonNull(chosen, "chosen must not be null");
    return new Account(
        id,
        signInName,
        chosen,
        membership,
        active,
        changeRequired,
        failedSignIns,
        lockedUntil,
        createdAt);
  }

  /**
   * @return the account's identity, which is also its equality
   */
  public UserId id() {
    return id;
  }

  /**
   * @return the normalised name it signs in with; personal data, so never log it
   */
  public SignInName signInName() {
    return signInName;
  }

  /**
   * @return the encoded password, for the hasher adapter to check a candidate against
   */
  public PasswordHash passwordHash() {
    return passwordHash;
  }

  /**
   * @return what the account is a member of: one organisation, or the platform
   */
  public Membership membership() {
    return membership;
  }

  /**
   * @return whether the account may be used at all; a disabled account is refused at sign-in and on
   *     every request, lock or no lock
   */
  public boolean active() {
    return active;
  }

  /**
   * @return whether the password is one somebody else chose and must be replaced before anything
   *     else is done
   */
  public boolean passwordChangeRequired() {
    return passwordChangeRequired;
  }

  /**
   * @return consecutive failed sign-ins counted since the last success or unlock; never negative.
   *     It is not cleared when a lock runs out: the first failure after that starts a fresh run at
   *     one
   */
  public int failedSignIns() {
    return failedSignIns;
  }

  /**
   * @return when the current lock ends, empty if there is none. A past instant means a lock that
   *     has run out and has not yet been cleared; use {@link #isLockedAt} to ask whether one is
   *     running
   */
  public Optional<Instant> lockedUntil() {
    return lockedUntil;
  }

  /**
   * @return when the account was provisioned
   */
  public Instant createdAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Account account && id.equals(account.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  /** Prints the identity and nothing else: the sign-in name is personal data. */
  @Override
  public String toString() {
    return "Account[id=%s]".formatted(id);
  }

  private TenantMembership requireTenantMembership() {
    if (membership instanceof TenantMembership tenant) {
      return tenant;
    }
    throw new OperatorBelongsToNoOrganisationException(id);
  }

  private static boolean isAnUnlinkedEmployee(Membership membership) {
    return membership instanceof TenantMembership tenant
        && tenant.role() == Role.EMPLOYEE
        && tenant.employee().isEmpty();
  }

  private Account replacingMembership(Membership changed) {
    return new Account(
        id,
        signInName,
        passwordHash,
        changed,
        active,
        passwordChangeRequired,
        failedSignIns,
        lockedUntil,
        createdAt);
  }
}
