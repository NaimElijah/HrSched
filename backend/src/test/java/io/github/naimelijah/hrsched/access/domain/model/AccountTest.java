package io.github.naimelijah.hrsched.access.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What an account is, and the lockout arithmetic it owns.
 *
 * <p>The lockout numbers here are invented for the test. No threshold or duration in this codebase
 * is a constant: both are configuration, and the policy is passed in so that this class can be
 * tested without choosing one.
 *
 * <p>The two refusals an operator account raises are the ones worth reading twice. An operator
 * belongs to no organisation, so a role and an employee link are not merely unset on one - they are
 * unaskable, and the type refuses rather than quietly ignoring the request.
 */
class AccountTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final Instant CREATED_AT = Instant.parse("2026-09-01T09:00:00Z");
  private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");
  private static final SignInName ADA = new SignInName("ada@example.com");
  private static final PasswordHash HASH = new PasswordHash("{bcrypt}$2a$04$invented.test.value");
  private static final LockoutPolicy LOCK_AFTER_THREE =
      new LockoutPolicy(3, Duration.ofMinutes(15));

  @Test
  void GivenATenantAccount_WhenAskedForItsActor_ThenItIsATenantActorCarryingItsEmployee() {
    EmployeeId ada = EmployeeId.newId();
    UserId id = UserId.newId();

    assertThat(tenant(id, Role.SCHEDULER, Optional.of(ada)).actor())
        .isEqualTo(new TenantActor(id, ACME, Role.SCHEDULER, Optional.of(ada)));
  }

  @Test
  void GivenAnOperatorAccount_WhenAskedForItsActor_ThenItIsTheOperator() {
    UserId id = UserId.newId();

    assertThat(operator(id).actor()).isEqualTo(new Operator(id));
  }

  @Test
  void GivenATenantAccount_WhenAskedForItsOrganisation_ThenItIsTheOneItBelongsTo() {
    assertThat(tenant(UserId.newId(), Role.ADMIN, Optional.empty()).organisation()).contains(ACME);
  }

  @Test
  void GivenAnOperatorAccount_WhenAskedForItsOrganisation_ThenThereIsNone() {
    assertThat(operator(UserId.newId()).organisation()).isEmpty();
  }

  @Test
  void
      GivenThresholdMinusOneFailures_WhenOneMoreFails_ThenTheAccountIsLockedForTheConfiguredDuration() {
    Account account = tenant(UserId.newId(), Role.EMPLOYEE, Optional.empty());

    Account twiceFailed =
        account
            .recordFailedSignIn(NOW, LOCK_AFTER_THREE)
            .recordFailedSignIn(NOW.plusSeconds(1), LOCK_AFTER_THREE);
    assertThat(twiceFailed.isLockedAt(NOW.plusSeconds(1))).isFalse();

    Account locked = twiceFailed.recordFailedSignIn(NOW.plusSeconds(2), LOCK_AFTER_THREE);

    assertThat(locked.failedSignIns()).isEqualTo(3);
    assertThat(locked.lockedUntil()).contains(NOW.plusSeconds(2).plus(Duration.ofMinutes(15)));
    assertThat(locked.isLockedAt(NOW.plusSeconds(2))).isTrue();
  }

  @Test
  void GivenALockedAccount_WhenTheDurationHasPassed_ThenItIsNoLongerLocked() {
    Account locked = lockedAt(NOW);
    Instant lockEnds = NOW.plus(Duration.ofMinutes(15));

    assertThat(locked.isLockedAt(lockEnds.minusMillis(1))).isTrue();
    assertThat(locked.isLockedAt(lockEnds))
        .describedAs("the lock ends at the instant it expires, not after it")
        .isFalse();
    assertThat(locked.isLockedAt(lockEnds.plusSeconds(1))).isFalse();
  }

  @Test
  void GivenAnExpiredLock_WhenSignInFailsAgain_ThenTheCountRestarts() {
    Account locked = lockedAt(NOW);
    Instant afterTheLock = NOW.plus(Duration.ofMinutes(16));

    Account failedAgain = locked.recordFailedSignIn(afterTheLock, LOCK_AFTER_THREE);

    assertThat(failedAgain.failedSignIns())
        .describedAs("a lock that ran out leaves nothing behind, so a second one costs as much")
        .isEqualTo(1);
    assertThat(failedAgain.isLockedAt(afterTheLock)).isFalse();
    assertThat(failedAgain.lockedUntil()).isEmpty();
  }

  @Test
  void GivenASuccessfulSignIn_WhenRecorded_ThenFailuresAndLockAreCleared() {
    Account locked = lockedAt(NOW);

    Account signedIn = locked.recordSuccessfulSignIn();

    assertThat(signedIn.failedSignIns()).isZero();
    assertThat(signedIn.lockedUntil()).isEmpty();
    assertThat(signedIn.isLockedAt(NOW)).isFalse();
  }

  @Test
  void GivenAnAccountThatNeverFailed_WhenAskedWhetherItIsLocked_ThenItIsNot() {
    assertThat(tenant(UserId.newId(), Role.EMPLOYEE, Optional.empty()).isLockedAt(NOW)).isFalse();
  }

  @Test
  void GivenAnOperatorAccount_WhenGivenARole_ThenItIsRefused() {
    Account account = operator(UserId.newId());

    assertThatThrownBy(() -> account.withRole(Role.ADMIN))
        .isInstanceOf(OperatorBelongsToNoOrganisationException.class);
  }

  @Test
  void GivenAnOperatorAccount_WhenLinkedToAnEmployee_ThenItIsRefused() {
    Account account = operator(UserId.newId());
    Optional<EmployeeId> ada = Optional.of(EmployeeId.newId());

    assertThatThrownBy(() -> account.linkedTo(ada))
        .isInstanceOf(OperatorBelongsToNoOrganisationException.class);
  }

  @Test
  void GivenATenantAccount_WhenGivenAnotherRole_ThenItKeepsEverythingElse() {
    Account account = tenant(UserId.newId(), Role.EMPLOYEE, Optional.of(EmployeeId.newId()));

    Account promoted = account.withRole(Role.SCHEDULER);

    assertThat(promoted.actor())
        .isInstanceOfSatisfying(
            TenantActor.class, actor -> assertThat(actor.role()).isEqualTo(Role.SCHEDULER));
    assertThat(promoted.signInName()).isEqualTo(account.signInName());
    assertThat(promoted.organisation()).isEqualTo(account.organisation());
  }

  @Test
  void GivenATenantAccount_WhenLinkedToAnEmployee_ThenItsActorCarriesThem() {
    EmployeeId ada = EmployeeId.newId();
    Account account = tenant(UserId.newId(), Role.EMPLOYEE, Optional.empty());

    Account linked = account.linkedTo(Optional.of(ada));

    assertThat(linked.actor())
        .isInstanceOfSatisfying(
            TenantActor.class, actor -> assertThat(actor.employee()).contains(ada));
  }

  @Test
  void GivenALinkedTenantAccount_WhenUnlinked_ThenItsActorCarriesNobody() {
    Account account = tenant(UserId.newId(), Role.EMPLOYEE, Optional.of(EmployeeId.newId()));

    Account unlinked = account.linkedTo(Optional.empty());

    assertThat(unlinked.actor())
        .isInstanceOfSatisfying(TenantActor.class, actor -> assertThat(actor.employee()).isEmpty());
  }

  @Test
  void GivenAnActiveAccount_WhenDisabled_ThenItIsNoLongerActive() {
    Account account = tenant(UserId.newId(), Role.ADMIN, Optional.empty());

    assertThat(account.active()).isTrue();
    assertThat(account.disabled().active()).isFalse();
    assertThat(account.disabled().enabled().active()).isTrue();
  }

  @Test
  void GivenANewPassword_WhenSet_ThenItReplacesTheHashAndSaysWhetherAChangeIsStillDue() {
    Account account = tenant(UserId.newId(), Role.ADMIN, Optional.empty());
    PasswordHash chosen = new PasswordHash("{bcrypt}$2a$04$another.invented.test.value");

    Account changed = account.withPassword(chosen, false);

    assertThat(changed.passwordHash()).isEqualTo(chosen);
    assertThat(changed.passwordChangeRequired()).isFalse();
    assertThat(account.withPassword(chosen, true).passwordChangeRequired()).isTrue();
  }

  @Test
  void GivenTwoAccountsSharingASignInName_WhenComparingThem_ThenIdentityDecides() {
    UserId id = UserId.newId();

    assertThat(tenant(id, Role.ADMIN, Optional.empty()))
        .isEqualTo(tenant(id, Role.EMPLOYEE, Optional.empty()))
        .isNotEqualTo(tenant(UserId.newId(), Role.ADMIN, Optional.empty()));
  }

  @Test
  void GivenAnAccount_WhenPrinted_ThenTheSignInNameIsNotShown() {
    assertThat(tenant(UserId.newId(), Role.ADMIN, Optional.empty()).toString())
        .doesNotContain("ada@example.com");
  }

  @Test
  void GivenANegativeFailureCount_WhenRebuildingAnAccount_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                new Account(
                    UserId.newId(),
                    ADA,
                    HASH,
                    new TenantMembership(ACME, Role.ADMIN, Optional.empty()),
                    true,
                    false,
                    -1,
                    Optional.empty(),
                    CREATED_AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenANullMembership_WhenCreatingAnAccount_ThenItIsRefused() {
    assertThatThrownBy(() -> new Account(UserId.newId(), ADA, HASH, null, false, CREATED_AT))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenANullIdentity_WhenCreatingAnAccount_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                new Account(
                    null,
                    ADA,
                    HASH,
                    new TenantMembership(ACME, Role.ADMIN, Optional.empty()),
                    false,
                    CREATED_AT))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenANewAccount_WhenCreated_ThenItIsActiveAndHasNoFailuresOrLock() {
    Account account = tenant(UserId.newId(), Role.ADMIN, Optional.empty());

    assertThat(account.active()).isTrue();
    assertThat(account.failedSignIns()).isZero();
    assertThat(account.lockedUntil()).isEmpty();
    assertThat(account.createdAt()).isEqualTo(CREATED_AT);
  }

  private static Account tenant(UserId id, Role role, Optional<EmployeeId> employee) {
    return new Account(
        id, ADA, HASH, new TenantMembership(ACME, role, employee), false, CREATED_AT);
  }

  private static Account operator(UserId id) {
    return new Account(id, ADA, HASH, new OperatorMembership(), false, CREATED_AT);
  }

  /** An account that reached the threshold at {@code now}, so its lock runs from there. */
  private static Account lockedAt(Instant now) {
    return tenant(UserId.newId(), Role.EMPLOYEE, Optional.empty())
        .recordFailedSignIn(now, LOCK_AFTER_THREE)
        .recordFailedSignIn(now, LOCK_AFTER_THREE)
        .recordFailedSignIn(now, LOCK_AFTER_THREE);
  }
}
