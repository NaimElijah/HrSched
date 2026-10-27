package io.github.naimelijah.hrsched.access.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.LockoutPolicy;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What is recorded when a password change is refused for its current password.
 *
 * <p>The caller is told the same thing either way: a locked account's refusal is indistinguishable
 * from a wrong password's ({@code LockedAccountPasswordChangeHttpTest}), on purpose. The record is
 * where the two must be told apart, as sign-in's are: a stream of "locked" refusals is somebody
 * still guessing behind a lock, which an operator needs to see, and a wrong password is not.
 *
 * <p>The reason is read from the {@code reason} field, the one sign-in's refusals carry. Which word
 * stands for each is not pinned, only what it means: a locked refusal's reason mentions the lock, a
 * wrong password's says it was wrong.
 *
 * <p>The log is read from the root logger rather than one class's, so the tests hold wherever the
 * line is written from.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every password and sign-in name here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class PasswordChangeRefusalLogHttpTest {

  /** Invented: the account's real password. */
  private static final String PASSWORD = "an invented password for a test";

  /** Invented: a guess that is not it. */
  private static final String WRONG_PASSWORD = "not the invented password";

  /** Invented: what the caller asks to change it to, long enough to satisfy the policy. */
  private static final String REPLACEMENT = "a different invented password";

  /** The field sign-in's refusals give their reason in. */
  private static final String REASON = "reason";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountRepository accounts;
  @Autowired private PasswordHasher passwords;
  @Autowired private LockoutPolicy lockout;

  private Logger root;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
    captured = new ListAppender<>();
    captured.start();
    root.addAppender(captured);
  }

  @AfterEach
  void releaseTheLog() {
    root.detachAppender(captured);
  }

  @Test
  void GivenALockedAccount_WhenAPasswordChangeIsRefused_ThenAWarningNamesTheActorAndTheLock()
      throws Exception {
    Account account = anOperator();
    String token = bearerFor(account);
    lockThroughSignIn(account);
    captured.list.clear();

    changePassword(token, PASSWORD);

    assertThat(captured.list)
        .describedAs(
            "a WARN line carrying the actor %s and a reason about the lock, among: %s",
            account.id().value(), everything())
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(valuesOf(event)).contains(account.id().value().toString());
              assertThat(reasonOf(event)).containsIgnoringCase("lock");
            });
  }

  @Test
  void
      GivenAWrongCurrentPassword_WhenAPasswordChangeIsRefused_ThenAWarningNamesTheActorAndAWrongPassword()
          throws Exception {
    Account account = anOperator();
    String token = bearerFor(account);
    captured.list.clear();

    changePassword(token, WRONG_PASSWORD);

    assertThat(captured.list)
        .describedAs(
            "a WARN line carrying the actor %s and a reason saying the password was wrong, among:"
                + " %s",
            account.id().value(), everything())
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(valuesOf(event)).contains(account.id().value().toString());
              assertThat(reasonOf(event)).containsIgnoringCase("wrong");
            });
  }

  @Test
  void
      GivenALockedAccount_WhenAPasswordChangeIsRefused_ThenNothingLoggedContainsAPasswordOrTheSignInName()
          throws Exception {
    Account account = anOperator();
    String token = bearerFor(account);
    lockThroughSignIn(account);
    captured.list.clear();

    changePassword(token, PASSWORD);

    assertThat(everything())
        .doesNotContain(PASSWORD)
        .doesNotContain(REPLACEMENT)
        .doesNotContain(account.signInName().value());
  }

  @Test
  void
      GivenAWrongCurrentPassword_WhenAPasswordChangeIsRefused_ThenNothingLoggedContainsAPasswordOrTheSignInName()
          throws Exception {
    Account account = anOperator();
    String token = bearerFor(account);
    captured.list.clear();

    changePassword(token, WRONG_PASSWORD);

    assertThat(everything())
        .doesNotContain(WRONG_PASSWORD)
        .doesNotContain(REPLACEMENT)
        .doesNotContain(account.signInName().value());
  }

  /** Fails sign-in as many times as the configured policy needs to lock the account. */
  private void lockThroughSignIn(Account account) throws Exception {
    for (int attempt = 0; attempt < lockout.threshold(); attempt++) {
      signIn(account, WRONG_PASSWORD);
    }
    assertThat(accounts.findForAuthentication(account.id()).orElseThrow().isLockedAt(Instant.now()))
        .describedAs("precondition: %d failed sign-ins lock the account", lockout.threshold())
        .isTrue();
  }

  private void changePassword(String authorization, String current) throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/auth/password")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                            .formatted(current, REPLACEMENT)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).describedAs("precondition: refused").isEqualTo(403);
  }

  private MockHttpServletResponse signIn(Account account, String password) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/sign-in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"signInName\":\"%s\",\"password\":\"%s\"}"
                        .formatted(account.signInName().value(), password)))
        .andReturn()
        .getResponse();
  }

  private String bearerFor(Account account) throws Exception {
    MockHttpServletResponse signedIn = signIn(account, PASSWORD);
    assertThat(signedIn.getStatus())
        .describedAs("precondition: the account signs in")
        .isEqualTo(200);
    return "Bearer " + JsonPath.read(signedIn.getContentAsString(), "$.accessToken");
  }

  private Account anOperator() {
    Account account =
        new Account(
            UserId.newId(),
            new SignInName("refused-change-%s@example.com".formatted(UUID.randomUUID())),
            passwords.hash(PASSWORD),
            new OperatorMembership(),
            false,
            Instant.now());
    accounts.save(account);
    return account;
  }

  private static List<KeyValuePair> pairsOf(ILoggingEvent event) {
    return event.getKeyValuePairs() == null ? List.of() : event.getKeyValuePairs();
  }

  private static List<String> valuesOf(ILoggingEvent event) {
    return pairsOf(event).stream().map(pair -> String.valueOf(pair.value)).toList();
  }

  private static String reasonOf(ILoggingEvent event) {
    return pairsOf(event).stream()
        .filter(pair -> REASON.equals(pair.key))
        .map(pair -> String.valueOf(pair.value).toLowerCase(Locale.ROOT))
        .findFirst()
        .orElse("");
  }

  /** Every message and every attached field, as one string to search. */
  private String everything() {
    return captured.list.stream()
        .map(
            event ->
                event.getLevel()
                    + " "
                    + event.getFormattedMessage()
                    + " "
                    + (event.getKeyValuePairs() == null ? "" : event.getKeyValuePairs()))
        .reduce("", (all, line) -> all + "\n" + line);
  }
}
