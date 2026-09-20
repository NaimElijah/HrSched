package io.github.naimelijah.hrsched.access.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.access.application.port.in.BootstrapOperatorCommand;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
  private final AccountService service =
      new AccountService(accounts, hasher, audit, Clock.fixed(NOW, ZoneOffset.UTC));

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
   * is what sign-in has to resolve to one account.
   */
  @Test
  void GivenATenantAccountHoldsTheName_WhenEnsuringTheOperator_ThenNothingIsCreated() {
    accounts.given(
        new Account(
            UserId.newId(),
            new SignInName(OPERATOR_NAME),
            new PasswordHash("{bcrypt}$2a$04$invented"),
            new TenantMembership(OrganisationId.newId(), Role.ADMIN, Optional.empty()),
            false,
            NOW));

    assertThat(service.ensureOperator(new BootstrapOperatorCommand(OPERATOR_NAME, A_PASSWORD)))
        .isFalse();
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

  /**
   * Stores accounts in a map, and answers the two questions the service asks of the real one.
   *
   * <p>Hand-written rather than mocked, like every other double here: insert-if-absent is a rule
   * this fake has to keep for the test to mean anything, and a mock would only replay whatever it
   * was told.
   */
  private static final class StubAccountRepository implements AccountRepository {

    private final List<Account> stored = new ArrayList<>();

    void given(Account account) {
      stored.add(account);
    }

    @Override
    public void save(Account account) {
      stored.removeIf(existing -> existing.id().equals(account.id()));
      stored.add(account);
    }

    @Override
    public boolean saveIfAbsent(Account account) {
      if (findBySignInName(account.signInName()).isPresent()) {
        return false;
      }
      stored.add(account);
      return true;
    }

    @Override
    public Optional<Account> find(OrganisationId organisation, UserId account) {
      return stored.stream()
          .filter(candidate -> candidate.organisation().filter(organisation::equals).isPresent())
          .filter(candidate -> candidate.id().equals(account))
          .findFirst();
    }

    @Override
    public List<Account> findAll(OrganisationId organisation) {
      return stored.stream()
          .filter(candidate -> candidate.organisation().filter(organisation::equals).isPresent())
          .toList();
    }

    @Override
    public Optional<Account> findBySignInName(SignInName name) {
      return stored.stream().filter(candidate -> candidate.signInName().equals(name)).findFirst();
    }

    @Override
    public void lockAdministrationOf(OrganisationId organisation) {
      // Nothing to serialise: this fake is never shared between threads.
    }
  }

  /**
   * Hashes by labelling the password rather than by computing anything. Reversible on purpose: a
   * real hash would make this test measure bcrypt's work factor instead of the service's rules.
   */
  private static final class StubPasswordHasher implements PasswordHasher {

    private static final String PREFIX = "{stub}";

    @Override
    public PasswordHash hash(String rawPassword) {
      Objects.requireNonNull(rawPassword, "rawPassword must not be null");
      return new PasswordHash(PREFIX + rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, PasswordHash hash) {
      return hash.value().equals(PREFIX + rawPassword);
    }

    @Override
    public int maximumBytes() {
      return 72;
    }
  }
}
