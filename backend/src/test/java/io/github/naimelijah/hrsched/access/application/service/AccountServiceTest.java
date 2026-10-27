package io.github.naimelijah.hrsched.access.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.access.application.port.in.BootstrapOperatorCommand;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.OperatorNameHeldByATenantException;
import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Specification of bootstrapping the platform operator, driven without Spring.
 *
 * <p>The rule worth reading twice is that bootstrapping never overwrites. Configuration outlives a
 * restart, so a runner that wrote the configured password every time would silently undo a password
 * the operator had changed — and would keep whatever was in the environment working forever.
 *
 * <p>Every password and name here is invented for the test.
 */
class AccountServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");
  private static final String OPERATOR_NAME = "operator@example.com";
  private static final String A_PASSWORD = "an invented password for a test";

  private final StubAccountRepository accounts = new StubAccountRepository();
  private final StubPasswordHasher hasher = new StubPasswordHasher();
  private final RecordingAuditTrail audit = new RecordingAuditTrail();

  /**
   * Bootstrapping touches neither sessions nor the workforce, and deliberately not the password
   * policy either: the configured password is the deployment's own choice and is not assessed
   * (ADR-029). Those three collaborators are therefore wired to doubles that refuse, so that a
   * bootstrap which started using one would fail here rather than quietly work.
   */
  private final AccountService service =
      new AccountService(
          accounts,
          new RecordingSessionRevocations(),
          new StubEmployeeDirectory(),
          hasher,
          new PasswordPolicy(1, hasher.maximumBytes()),
          audit,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void
      GivenNoAccountWithTheName_WhenEnsuringTheOperator_ThenAnOperatorIsCreatedThatMustChangeItsPassword() {
    boolean created =
        service.ensureOperator(new BootstrapOperatorCommand(OPERATOR_NAME, A_PASSWORD));

    assertThat(created).isTrue();
    Account operator = accounts.findBySignInName(new SignInName(OPERATOR_NAME)).orElseThrow();
    assertThat(operator.membership()).isEqualTo(new OperatorMembership());
    assertThat(operator.active()).isTrue();
    assertThat(operator.passwordChangeRequired()).isTrue();
    assertThat(operator.failedSignIns()).isZero();
    assertThat(operator.createdAt()).isEqualTo(NOW);
    assertThat(hasher.matches(A_PASSWORD, operator.passwordHash())).isTrue();
  }

  @Test
  void
      GivenAnAccountWithTheName_WhenEnsuringTheOperator_ThenNothingIsCreatedAndItsPasswordIsUntouched() {
    service.ensureOperator(new BootstrapOperatorCommand(OPERATOR_NAME, A_PASSWORD));
    Account before = accounts.findBySignInName(new SignInName(OPERATOR_NAME)).orElseThrow();

    boolean created =
        service.ensureOperator(new BootstrapOperatorCommand(OPERATOR_NAME, "a different password"));

    assertThat(created).isFalse();
    Account after = accounts.findBySignInName(new SignInName(OPERATOR_NAME)).orElseThrow();
    assertThat(after.id()).isEqualTo(before.id());
    assertThat(after.passwordHash()).isEqualTo(before.passwordHash());
  }

  /**
   * An account already holding the name blocks the bootstrap whatever kind it is, because the name
   * is what sign-in has to resolve to one account. A tenant's holding it is refused rather than
   * reported as an operator already present: no operator exists then, and none can be created, so a
   * quiet return would start a platform nobody can administer.
   */
  @Test
  void
      GivenATenantAccountHoldsTheName_WhenEnsuringTheOperator_ThenItIsRefusedAndNothingIsCreated() {
    Account tenant =
        new Account(
            UserId.newId(),
            new SignInName(OPERATOR_NAME),
            new PasswordHash("{bcrypt}$2a$04$invented"),
            new TenantMembership(OrganisationId.newId(), Role.ADMIN, Optional.empty()),
            false,
            NOW);
    accounts.given(tenant);

    assertThatThrownBy(
            () -> service.ensureOperator(new BootstrapOperatorCommand(OPERATOR_NAME, A_PASSWORD)))
        .isInstanceOf(OperatorNameHeldByATenantException.class);

    Account holder = accounts.findBySignInName(new SignInName(OPERATOR_NAME)).orElseThrow();
    assertThat(holder.id()).isEqualTo(tenant.id());
    assertThat(audit.of(AccessChange.OPERATOR_BOOTSTRAPPED)).isEmpty();
  }

  @Test
  void GivenTheOperatorIsCreated_WhenRecorded_ThenTheAuditEntryNamesNoOrganisation() {
    service.ensureOperator(new BootstrapOperatorCommand(OPERATOR_NAME, A_PASSWORD));

    List<AuditedChange> recorded = audit.of(AccessChange.OPERATOR_BOOTSTRAPPED);
    assertThat(recorded).hasSize(1);
    assertThat(recorded.getFirst().organisation())
        .describedAs("an operator belongs to no organisation")
        .isEmpty();
    Account operator = accounts.findBySignInName(new SignInName(OPERATOR_NAME)).orElseThrow();
    assertThat(recorded.getFirst().subject()).isEqualTo(operator.id().value());
    assertThat(recorded.getFirst().actor()).isEqualTo(operator.id());
  }

  @Test
  void GivenTheOperatorAlreadyExists_WhenEnsuringIt_ThenNothingIsRecorded() {
    service.ensureOperator(new BootstrapOperatorCommand(OPERATOR_NAME, A_PASSWORD));
    audit.forget();

    service.ensureOperator(new BootstrapOperatorCommand(OPERATOR_NAME, A_PASSWORD));

    assertThat(audit.changes()).isEmpty();
  }

  /** The name is normalised on the way in, so configuration that shouts still finds the account. */
  @Test
  void GivenTheNameInAnotherCase_WhenEnsuringTheOperatorAgain_ThenNothingIsCreated() {
    service.ensureOperator(new BootstrapOperatorCommand(OPERATOR_NAME, A_PASSWORD));

    assertThat(
            service.ensureOperator(
                new BootstrapOperatorCommand("  OPERATOR@EXAMPLE.COM ", A_PASSWORD)))
        .isFalse();
  }

  @Test
  void GivenABootstrapCommand_WhenPrinted_ThenNeitherTheNameNorThePasswordIsShown() {
    String printed = new BootstrapOperatorCommand(OPERATOR_NAME, A_PASSWORD).toString();

    assertThat(printed).doesNotContain(OPERATOR_NAME).doesNotContain(A_PASSWORD);
  }
}
