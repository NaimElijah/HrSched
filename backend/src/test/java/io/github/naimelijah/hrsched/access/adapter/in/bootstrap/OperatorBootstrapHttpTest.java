package io.github.naimelijah.hrsched.access.adapter.in.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * The configured operator exists, against the real application context and the real database.
 *
 * <p>Drives the runner rather than relying on the one that ran at startup, because {@code
 * PostgresDatabase.empty()} truncates every table between tests and may well have removed the
 * bootstrapped operator before this class runs. That the runner can be asked again, and creates the
 * account when it is missing and leaves it alone when it is not, is the property that matters.
 *
 * <p>Annotated identically to the other tests that share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class OperatorBootstrapHttpTest {

  @Autowired private OperatorBootstrap bootstrap;
  @Autowired private BootstrapOperatorProperties properties;
  @Autowired private AccountRepository accounts;

  @Test
  void GivenBootstrapConfiguration_WhenTheApplicationStarts_ThenTheOperatorExists()
      throws Exception {
    bootstrap.run(null);

    Account operator =
        accounts.findBySignInName(new SignInName(properties.signInName())).orElseThrow();
    assertThat(operator.membership()).isEqualTo(new OperatorMembership());
    assertThat(operator.organisation()).isEmpty();
    assertThat(operator.active()).isTrue();
    assertThat(operator.passwordChangeRequired())
        .describedAs("a password somebody else chose must be changed before anything else")
        .isTrue();
  }

  @Test
  void GivenTheOperatorAlreadyExists_WhenTheRunnerRunsAgain_ThenTheSameAccountIsKept()
      throws Exception {
    bootstrap.run(null);
    Account before =
        accounts.findBySignInName(new SignInName(properties.signInName())).orElseThrow();

    bootstrap.run(null);

    Account after =
        accounts.findBySignInName(new SignInName(properties.signInName())).orElseThrow();
    assertThat(after.id()).isEqualTo(before.id());
    assertThat(after.passwordHash()).isEqualTo(before.passwordHash());
  }
}
