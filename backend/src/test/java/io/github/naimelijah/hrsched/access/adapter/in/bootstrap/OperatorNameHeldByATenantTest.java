package io.github.naimelijah.hrsched.access.adapter.in.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.access.application.port.in.BootstrapOperatorUseCase;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorNameHeldByATenantException;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * The configured operator's sign-in name already belongs to an ordinary tenant account.
 *
 * <p>Then no operator exists, and none can be created: the name is platform-unique. Starting up as
 * though the operator were there - "already exists and was left unchanged" - would leave a
 * deployment with nobody able to onboard an organisation, and a startup log saying all is well.
 * Startup must fail instead, loudly, without naming the account, since a sign-in name is a
 * person's.
 *
 * <p>The case where the name is held by the operator itself, which must still start quietly and
 * leave it alone, is {@code OperatorBootstrapHttpTest} and {@code AccountServiceTest}.
 *
 * <p>Drives the runner against the application's own use case and the real database, so the refusal
 * is observed where startup sees it, whichever layer comes to raise it.
 *
 * <p>Annotated identically to the other tests that share one Spring context.
 *
 * <p>Every name and password here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class OperatorNameHeldByATenantTest {

  /** Invented: the tenant account's own password. */
  private static final String TENANT_PASSWORD = "an invented password for a test";

  /** Invented: the password configured for the operator that is to be created. */
  private static final String CONFIGURED_PASSWORD = "an invented operator password for a test";

  @Autowired private BootstrapOperatorUseCase useCase;
  @Autowired private AccountRepository accounts;
  @Autowired private PasswordHasher passwords;

  @Test
  void GivenTheConfiguredNameIsATenantsAccount_WhenTheBootstrapRuns_ThenStartupFails() {
    Account tenant = aTenantAccount();

    Throwable thrown = failureOf(configuredWith(tenant.signInName().value()));

    assertThat(thrown)
        .describedAs("no operator exists, so the bootstrap must not report one as present")
        .isInstanceOf(OperatorNameHeldByATenantException.class);
  }

  /**
   * The identity, not the name, is what lets whoever reads the startup log find the account that is
   * in the way.
   */
  @Test
  void
      GivenTheConfiguredNameIsATenantsAccount_WhenTheBootstrapRefuses_ThenTheFailureNamesTheHolderById() {
    Account tenant = aTenantAccount();

    Throwable thrown = failureOf(configuredWith(tenant.signInName().value()));

    assertThat(thrown).describedAs("precondition: startup fails").isNotNull();
    assertThat(stackTraceOf(thrown)).contains(tenant.id().value().toString());
  }

  @Test
  void
      GivenTheConfiguredNameIsATenantsAccount_WhenTheBootstrapRefuses_ThenTheFailureDoesNotNameTheAccount() {
    Account tenant = aTenantAccount();
    String name = tenant.signInName().value();

    Throwable thrown = failureOf(configuredWith(name));

    assertThat(thrown).describedAs("precondition: startup fails").isNotNull();
    assertThat(stackTraceOf(thrown))
        .describedAs("a startup failure is logged, and a sign-in name is a person's")
        .doesNotContain(name);
  }

  @Test
  void GivenTheConfiguredNameIsATenantsAccount_WhenTheBootstrapRuns_ThenTheTenantIsUntouched() {
    Account tenant = aTenantAccount();

    failureOf(configuredWith(tenant.signInName().value()));

    Account after = accounts.findBySignInName(tenant.signInName()).orElseThrow();
    assertThat(after.id()).isEqualTo(tenant.id());
    assertThat(after.membership()).isEqualTo(tenant.membership());
    assertThat(after.passwordHash()).isEqualTo(tenant.passwordHash());
  }

  private OperatorBootstrap configuredWith(String signInName) {
    return new OperatorBootstrap(
        useCase, new BootstrapOperatorProperties(signInName, CONFIGURED_PASSWORD));
  }

  /**
   * @return what running the bootstrap threw, or null if it returned
   */
  private static Throwable failureOf(OperatorBootstrap bootstrap) {
    try {
      bootstrap.run(null);
      return null;
    } catch (RuntimeException thrown) {
      return thrown;
    }
  }

  private Account aTenantAccount() {
    OrganisationId organisation = OrganisationId.newId();
    PostgresDatabase.given(organisation);
    Account account =
        new Account(
            UserId.newId(),
            new SignInName("tenant-admin-%s@example.com".formatted(UUID.randomUUID())),
            passwords.hash(TENANT_PASSWORD),
            new TenantMembership(organisation, Role.ADMIN, Optional.empty()),
            false,
            Instant.now());
    accounts.save(account);
    return account;
  }

  private static String stackTraceOf(Throwable thrown) {
    StringWriter written = new StringWriter();
    thrown.printStackTrace(new PrintWriter(written));
    return written.toString();
  }
}
