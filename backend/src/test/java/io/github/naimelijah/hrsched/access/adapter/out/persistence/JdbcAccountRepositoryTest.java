package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.EmployeeAlreadyLinkedException;
import io.github.naimelijah.hrsched.access.domain.model.LockoutPolicy;
import io.github.naimelijah.hrsched.access.domain.model.Membership;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInNameTakenException;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tenant predicate this adapter promises, the two collisions it names, and the lock it takes.
 *
 * <p>An account says what a colleague may do and whether somebody has been guessing their password,
 * so a read that forgot its organisation would hand one customer another's staff list. The operator
 * tests are the sharpest of these: an operator's row carries no organisation at all, and must
 * therefore never appear in a tenant's listing.
 *
 * <p>Every password hash here is invented and hashes nothing. Every lockout number is invented too.
 */
class JdbcAccountRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final PasswordHash HASH = new PasswordHash("{bcrypt}$2a$04$invented.test.value");
  private static final Instant CREATED_AT = Instant.parse("2026-09-01T09:00:00Z");

  /** How long a probing transaction waits for the lock before giving up. */
  private static final String A_PROBE_WAIT = "250ms";

  /**
   * PostgreSQL's {@code lock_not_available}: the statement waited for a lock until {@code
   * lock_timeout} ran out. Asserted by SQLState because Spring reports it as an uncategorised
   * exception rather than as a lock failure, so the type would say nothing about the wait.
   */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  /** Long enough that a lock free to take is never mistaken for one held. */
  private static final int STUCK_AFTER_SECONDS = 30;

  private final JdbcAccountRepository repository =
      new JdbcAccountRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
    PostgresDatabase.given(ACME, RIVAL);
  }

  @Test
  void GivenASavedTenantAccount_WhenFound_ThenEveryFieldRoundTrips() {
    EmployeeId ada = EmployeeId.newId();
    Account stored =
        new Account(
                UserId.newId(),
                new SignInName("ada@example.com"),
                HASH,
                new TenantMembership(ACME, Role.SCHEDULER, Optional.of(ada)),
                true,
                CREATED_AT)
            .recordFailedSignIn(CREATED_AT, new LockoutPolicy(1, Duration.ofMinutes(15)));
    repository.save(stored);

    Account found = repository.find(ACME, stored.id()).orElseThrow();

    assertThat(found.id()).isEqualTo(stored.id());
    assertThat(found.signInName()).isEqualTo(new SignInName("ada@example.com"));
    assertThat(found.passwordHash()).isEqualTo(HASH);
    assertThat(found.membership())
        .isEqualTo(new TenantMembership(ACME, Role.SCHEDULER, Optional.of(ada)));
    assertThat(found.active()).isTrue();
    assertThat(found.passwordChangeRequired()).isTrue();
    assertThat(found.failedSignIns()).isEqualTo(1);
    assertThat(found.lockedUntil()).contains(CREATED_AT.plus(Duration.ofMinutes(15)));
    assertThat(found.createdAt()).isEqualTo(CREATED_AT);
  }

  @Test
  void GivenAnAccountOfAnotherOrganisation_WhenFindingItInOurs_ThenNothingIsReturned() {
    Account theirs = tenant(RIVAL, "theirs@example.com", Role.ADMIN);
    repository.save(theirs);

    assertThat(repository.find(ACME, theirs.id())).isEmpty();
  }

  @Test
  void GivenAnotherOrganisationsAccounts_WhenListingOurs_ThenOnlyOursAreReturned() {
    Account ours = tenant(ACME, "ours@example.com", Role.ADMIN);
    Account theirs = tenant(RIVAL, "theirs@example.com", Role.ADMIN);
    repository.save(ours);
    repository.save(theirs);

    assertThat(repository.findAll(ACME)).extracting(Account::id).containsExactly(ours.id());
  }

  /**
   * An operator belongs to no organisation, so it is not somebody a tenant's administrator may see
   * or change. Its absence here is enforced by the column rather than by a guard above.
   */
  @Test
  void GivenAnOperatorAccount_WhenListingAnOrganisationsAccounts_ThenItIsNotAmongThem() {
    repository.save(operator("operator@example.com"));
    Account ours = tenant(ACME, "ours@example.com", Role.ADMIN);
    repository.save(ours);

    assertThat(repository.findAll(ACME)).extracting(Account::id).containsExactly(ours.id());
  }

  @Test
  void GivenASavedOperatorAccount_WhenFoundBySignInName_ThenItIsReturned() {
    Account stored = operator("operator@example.com");
    repository.save(stored);

    Account found =
        repository.findBySignInName(new SignInName("operator@example.com")).orElseThrow();

    assertThat(found.id()).isEqualTo(stored.id());
    assertThat(found.membership()).isEqualTo(new OperatorMembership());
    assertThat(found.organisation()).isEmpty();
  }

  @Test
  void GivenAName_WhenFindingBySignInNameInADifferentCase_ThenTheSameAccountIsReturned() {
    Account stored = tenant(ACME, "Ada@Example.com", Role.EMPLOYEE);
    repository.save(stored);

    assertThat(repository.findBySignInName(new SignInName("  ADA@EXAMPLE.COM ")))
        .map(Account::id)
        .contains(stored.id());
  }

  @Test
  void GivenNoAccountUnderThatName_WhenFindingBySignInName_ThenNothingIsReturned() {
    assertThat(repository.findBySignInName(new SignInName("nobody@example.com"))).isEmpty();
  }

  @Test
  void GivenATakenSignInNameInAnyOrganisation_WhenSaving_ThenItIsRefused() {
    repository.save(tenant(RIVAL, "ada@example.com", Role.ADMIN));
    Account sameName = tenant(ACME, "ada@example.com", Role.EMPLOYEE);

    assertThatThrownBy(() -> repository.save(sameName))
        .isInstanceOf(SignInNameTakenException.class)
        .hasMessageNotContaining("ada@example.com");
  }

  @Test
  void GivenAnEmployeeAlreadyLinkedInTheOrganisation_WhenLinkingAnotherAccount_ThenItIsRefused() {
    EmployeeId ada = EmployeeId.newId();
    repository.save(tenant(ACME, "first@example.com", Role.EMPLOYEE).linkedTo(Optional.of(ada)));
    Account second = tenant(ACME, "second@example.com", Role.EMPLOYEE).linkedTo(Optional.of(ada));

    assertThatThrownBy(() -> repository.save(second))
        .isInstanceOfSatisfying(
            EmployeeAlreadyLinkedException.class,
            refused -> assertThat(refused.employee()).isEqualTo(ada));
  }

  /**
   * The link is unique within an organisation, not across the platform: two customers may each
   * employ somebody, and the identifiers are theirs alone.
   */
  @Test
  void GivenAnEmployeeLinkedInAnotherOrganisation_WhenLinkingOneOfOurs_ThenItIsAccepted() {
    EmployeeId shared = EmployeeId.newId();
    repository.save(
        tenant(RIVAL, "theirs@example.com", Role.EMPLOYEE).linkedTo(Optional.of(shared)));
    Account ours = tenant(ACME, "ours@example.com", Role.EMPLOYEE).linkedTo(Optional.of(shared));

    assertThatCode(() -> repository.save(ours)).doesNotThrowAnyException();
  }

  @Test
  void GivenAnUpdatedAccount_WhenSavedAgain_ThenTheChangeIsStored() {
    Account stored = tenant(ACME, "ada@example.com", Role.EMPLOYEE);
    repository.save(stored);

    repository.save(stored.withRole(Role.SCHEDULER).disabled());

    Account found = repository.find(ACME, stored.id()).orElseThrow();
    assertThat(found.membership())
        .isEqualTo(new TenantMembership(ACME, Role.SCHEDULER, Optional.empty()));
    assertThat(found.active()).isFalse();
    assertThat(repository.findAll(ACME)).hasSize(1);
  }

  /**
   * {@code save} is the one repository method carrying no {@code OrganisationId}, so the upsert is
   * the only thing standing between a re-used identity and another tenant's row. Without it, saving
   * moved the account into the caller's organisation rather than refusing.
   */
  @Test
  void GivenAnIdentityHeldByAnotherOrganisation_WhenSavingOverIt_ThenItIsRefused() {
    Account theirs = tenant(RIVAL, "theirs@example.com", Role.SCHEDULER);
    repository.save(theirs);

    assertThatThrownBy(() -> repository.save(overwriting(theirs, anAdminOf(ACME))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another organisation");
  }

  @Test
  void GivenAnIdentityHeldByAnotherOrganisation_WhenSavingOverIt_ThenTheirRowIsUntouched() {
    Account theirs = tenant(RIVAL, "theirs@example.com", Role.SCHEDULER);
    repository.save(theirs);

    assertThatThrownBy(() -> repository.save(overwriting(theirs, anAdminOf(ACME))))
        .isInstanceOf(IllegalStateException.class);

    Account stored = repository.find(RIVAL, theirs.id()).orElseThrow();
    assertThat(stored.signInName()).isEqualTo(theirs.signInName());
    assertThat(stored.membership()).isEqualTo(theirs.membership());
    assertThat(repository.find(ACME, theirs.id())).isEmpty();
  }

  /** Without the guard, a tenant account saved under the operator's identity made it an Admin. */
  @Test
  void GivenTheOperatorsIdentity_WhenATenantAccountIsSavedOverIt_ThenTheOperatorStaysOne() {
    Account operator = operator("operator@example.com");
    repository.save(operator);

    assertThatThrownBy(() -> repository.save(overwriting(operator, anAdminOf(ACME))))
        .isInstanceOf(IllegalStateException.class);

    assertThat(repository.findBySignInName(new SignInName("operator@example.com")))
        .get()
        .extracting(Account::membership)
        .isEqualTo(new OperatorMembership());
  }

  /** Without the guard, the operator saved under a tenant's identity turned it into an operator. */
  @Test
  void GivenATenantsIdentity_WhenTheOperatorIsSavedOverIt_ThenTheTenantStaysOne() {
    Account theirs = tenant(RIVAL, "theirs@example.com", Role.SCHEDULER);
    repository.save(theirs);

    assertThatThrownBy(() -> repository.save(overwriting(theirs, new OperatorMembership())))
        .isInstanceOf(IllegalStateException.class);

    assertThat(repository.find(RIVAL, theirs.id()))
        .get()
        .extracting(Account::membership)
        .isEqualTo(theirs.membership());
  }

  /**
   * The operator's row carries no organisation, and {@code NULL = NULL} is not true, so a guard
   * comparing with {@code =} would refuse every save of it — and sign-in saves it after a failure.
   */
  @Test
  void GivenTheOperator_WhenSavedAgain_ThenTheChangeIsStored() {
    Account operator = operator("operator@example.com");
    repository.save(operator);

    repository.save(
        operator.recordFailedSignIn(CREATED_AT, new LockoutPolicy(3, Duration.ofMinutes(15))));

    assertThat(repository.findBySignInName(new SignInName("operator@example.com")))
        .get()
        .extracting(Account::failedSignIns)
        .isEqualTo(1);
  }

  @Test
  void GivenAFreeSignInName_WhenSavingIfAbsent_ThenItIsStoredAndTrueIsReturned() {
    Account operator = operator("operator@example.com");

    assertThat(repository.saveIfAbsent(operator)).isTrue();
    assertThat(repository.findBySignInName(new SignInName("operator@example.com")))
        .map(Account::id)
        .contains(operator.id());
  }

  /**
   * The bootstrap depends on this: an operator whose password has since been changed must not be
   * put back to whatever is still in the environment on the next restart.
   */
  @Test
  void GivenATakenSignInName_WhenSavingIfAbsent_ThenNothingChangesAndFalseIsReturned() {
    Account first = operator("operator@example.com");
    repository.saveIfAbsent(first);
    Account second =
        new Account(
            UserId.newId(),
            new SignInName("operator@example.com"),
            new PasswordHash("{bcrypt}$2a$04$a.different.invented.value"),
            new OperatorMembership(),
            false,
            CREATED_AT);

    assertThat(repository.saveIfAbsent(second)).isFalse();

    Account stored =
        repository.findBySignInName(new SignInName("operator@example.com")).orElseThrow();
    assertThat(stored.id()).isEqualTo(first.id());
    assertThat(stored.passwordHash()).isEqualTo(HASH);
  }

  /**
   * Outside a transaction the lock would be released the moment the statement taking it ended, so
   * nothing would wait for anything and the caller would not know. It is refused instead, so a use
   * case that is not wrapped in its transaction fails loudly rather than racing quietly.
   */
  @Test
  void GivenNoTransaction_WhenLockingAdministration_ThenItIsRefused() {
    assertThatThrownBy(() -> repository.lockAdministrationOf(ACME))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * While one transaction holds an organisation's account administration, another asking for it
   * waits, and one asking for a different organisation's does not.
   *
   * <p>The waiting is shown by giving the second transaction a lock timeout, so a wait becomes a
   * refusal the test can see rather than a pause it would have to time. Single-threaded: the second
   * transaction runs on its own connection while the first is suspended, still holding the lock.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void GivenOneTransactionHoldsAnOrganisationsAdministration_WhenAnotherAsksForIt_ThenItWaits() {
    TransactionTemplate holder = new TransactionTemplate(PostgresDatabase.transactions());
    TransactionTemplate another = new TransactionTemplate(PostgresDatabase.transactions());
    another.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    holder.executeWithoutResult(
        held -> {
          repository.lockAdministrationOf(ACME);

          assertThatThrownBy(() -> another.executeWithoutResult(probe -> askBrieflyFor(ACME)))
              .rootCause()
              .isInstanceOfSatisfying(
                  SQLException.class,
                  cause -> assertThat(cause.getSQLState()).isEqualTo(LOCK_NOT_AVAILABLE));
          assertThatCode(() -> another.executeWithoutResult(probe -> askBrieflyFor(RIVAL)))
              .describedAs("another organisation's administration does not wait on this one's")
              .doesNotThrowAnyException();
        });

    assertThatCode(() -> another.executeWithoutResult(probe -> askBrieflyFor(ACME)))
        .describedAs("the lock ends with the transaction that took it")
        .doesNotThrowAnyException();
  }

  private static Account tenant(OrganisationId organisation, String signInName, Role role) {
    return new Account(
        UserId.newId(),
        new SignInName(signInName),
        HASH,
        new TenantMembership(organisation, role, Optional.empty()),
        false,
        CREATED_AT);
  }

  /**
   * @return ours, under their identity, with a sign-in name nobody holds, so that only the guard
   *     can refuse it
   */
  private static Account overwriting(Account theirs, Membership membership) {
    return new Account(
        theirs.id(), new SignInName("ours@example.com"), HASH, membership, false, CREATED_AT);
  }

  private static TenantMembership anAdminOf(OrganisationId organisation) {
    return new TenantMembership(organisation, Role.ADMIN, Optional.empty());
  }

  private static Account operator(String signInName) {
    return new Account(
        UserId.newId(),
        new SignInName(signInName),
        HASH,
        new OperatorMembership(),
        false,
        CREATED_AT);
  }

  /**
   * Asks for an organisation's administration, giving up after a short wait instead of waiting on.
   */
  private void askBrieflyFor(OrganisationId organisation) {
    PostgresDatabase.jdbcClient()
        .sql("SELECT set_config('lock_timeout', :wait, true)")
        .param("wait", A_PROBE_WAIT)
        .query(String.class)
        .single();
    repository.lockAdministrationOf(organisation);
  }
}
