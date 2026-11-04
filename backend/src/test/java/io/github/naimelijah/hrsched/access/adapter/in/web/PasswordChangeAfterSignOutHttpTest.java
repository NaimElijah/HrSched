package io.github.naimelijah.hrsched.access.adapter.in.web;

import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.STUCK_AFTER_SECONDS;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.awaitSomebodyWaitingOn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A sign-out must not be undone by a password change that was authenticated before it (#352).
 *
 * <p>The security filter reads the session without a lock, so a change can be authenticated, then
 * have its session ended before it commits. Without a check on the session as it stands when the
 * change writes, the change begins a new session and answers with a new refresh cookie, and
 * somebody who signed out on a shared computer has left their account to the next person.
 *
 * <p>The two tests are the two moments that can happen. In the first, the sign-out commits while
 * the change waits on the account, so a check made once the account is held would see it. In the
 * second, the sign-out is still uncommitted when the change reads the session and has reached its
 * write, so only a check that waits on the session's row would see it.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every password and sign-in name here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class PasswordChangeAfterSignOutHttpTest {

  /** Invented: the account's real password. */
  private static final String PASSWORD = "an invented password for a test";

  /** Invented: what the caller asks to change it to, long enough to satisfy the policy. */
  private static final String REPLACEMENT = "a different invented password";

  private static final String REQUESTED_WITH = "X-Requested-With";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountRepository accounts;
  @Autowired private PasswordHasher passwords;
  @Autowired private JwtDecoder decoder;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private JdbcClient jdbc;

  /**
   * The account's row is held so the change waits at {@code findForUpdate}, having already passed
   * the filter. The sign-out runs on a thread of its own: MockMvc runs on its caller's thread and a
   * use case joins an existing transaction, so sent from inside the holder's callback it would
   * become part of the holder's transaction rather than commit before the holder lets go.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void
      GivenASignOutCommittedWhileAPasswordChangeWaitsOnTheAccount_WhenTheChangeGoesOn_ThenItIsRefusedAndNoSessionBegins()
          throws Exception {
    Account account = anOperator();
    MockHttpServletResponse signedIn = signIn(account, PASSWORD);
    String bearer = bearerOf(signedIn);
    String refreshToken = refreshTokenOf(signedIn);
    int sessionsBefore = sessionsOf(account);

    ExecutorService changing = Executors.newSingleThreadExecutor();
    ExecutorService signingOut = Executors.newSingleThreadExecutor();
    AtomicReference<Future<MockHttpServletResponse>> change = new AtomicReference<>();
    MockHttpServletResponse answered;
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              held -> {
                jdbc.sql("SELECT id FROM account WHERE id = :account FOR UPDATE")
                    .param("account", account.id().value())
                    .query(UUID.class)
                    .single();
                int holder = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
                change.set(changing.submit(() -> changePassword(bearer)));
                awaitSomebodyWaitingOn(holder, change.get());
                assertThat(change.get().isDone())
                    .describedAs("precondition: the change is waiting on the account")
                    .isFalse();
                MockHttpServletResponse signOut =
                    answerOf(signingOut.submit(() -> signOut(refreshToken)));
                assertThat(signOut.getStatus())
                    .describedAs("precondition: the sign-out completes while the change waits")
                    .isEqualTo(204);
              });
      answered = answerOf(change.get());
    } finally {
      changing.shutdownNow();
      signingOut.shutdownNow();
    }

    assertRefusedHavingChangedNothing(answered, account, sessionsBefore);
  }

  /**
   * The uncommitted update stands for a sign-out in flight. The change reads the session as live,
   * because the revocation is not visible yet, runs its password comparisons and blocks on that row
   * when it reaches its write. The revocation commits under it, so a check made before the write
   * has already passed: the check has to be atomic with the write.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void
      GivenASignOutInFlightOnTheSessionsRow_WhenAPasswordChangeReachesItsWrite_ThenItIsRefusedAndNoSessionBegins()
          throws Exception {
    Account account = anOperator();
    MockHttpServletResponse signedIn = signIn(account, PASSWORD);
    String bearer = bearerOf(signedIn);
    UUID session = UUID.fromString(decoder.decode(accessTokenOf(signedIn)).getClaimAsString("sid"));
    int sessionsBefore = sessionsOf(account);

    ExecutorService changing = Executors.newSingleThreadExecutor();
    AtomicReference<Future<MockHttpServletResponse>> change = new AtomicReference<>();
    MockHttpServletResponse answered;
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              held -> {
                int revoked =
                    jdbc.sql(
                            "UPDATE sign_in_session SET revoked_at = :now, revocation = 'SIGNED_OUT'"
                                + " WHERE id = :session")
                        .param("now", Instant.now().atOffset(ZoneOffset.UTC))
                        .param("session", session)
                        .update();
                assertThat(revoked)
                    .describedAs("precondition: the session's own row is the one held")
                    .isEqualTo(1);
                int holder = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
                change.set(changing.submit(() -> changePassword(bearer)));
                awaitSomebodyWaitingOn(holder, change.get());
                assertThat(change.get().isDone())
                    .describedAs("precondition: the change is waiting on the session's row")
                    .isFalse();
              });
      answered = answerOf(change.get());
    } finally {
      changing.shutdownNow();
    }

    assertRefusedHavingChangedNothing(answered, account, sessionsBefore);
  }

  /**
   * The sessions are counted before signing in again, as a sign-in begins one of its own. The old
   * password is tried before the replacement because a refused attempt is counted against the
   * account, and a success clears the count: this order leaves neither result depending on the
   * other.
   */
  private void assertRefusedHavingChangedNothing(
      MockHttpServletResponse answered, Account account, int sessionsBefore) throws Exception {
    assertThat(answered.getStatus())
        .describedAs("a change whose session has ended is refused, as any request of that session")
        .isEqualTo(401);
    String body = answered.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.type"))
        .describedAs("the refusal's problem type")
        .isEqualTo("urn:hrsched:problem:unauthenticated");
    assertThat(JsonPath.<String>read(body, "$.title"))
        .describedAs("the refusal's problem title")
        .isEqualTo("Unauthenticated");
    assertThat(answered.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        .describedAs("the challenge names the token as no longer accepted")
        .startsWith("Bearer error=\"invalid_token\"");
    assertThat(answered.getHeader(HttpHeaders.SET_COOKIE))
        .describedAs("no refresh cookie for a browser that has signed out")
        .isNull();
    assertThat(sessionsOf(account))
        .describedAs("no session began from the change")
        .isEqualTo(sessionsBefore);
    assertThat(signIn(account, PASSWORD).getStatus())
        .describedAs("the old password is still the password")
        .isEqualTo(200);
    assertThat(signIn(account, REPLACEMENT).getStatus())
        .describedAs("the replacement was never stored")
        .isEqualTo(401);
  }

  private int sessionsOf(Account account) {
    return jdbc.sql("SELECT count(*) FROM sign_in_session WHERE account_id = :account")
        .param("account", account.id().value())
        .query(Integer.class)
        .single();
  }

  /** Waits for a request on another thread, failing the test rather than hanging if it is stuck. */
  private static MockHttpServletResponse answerOf(Future<MockHttpServletResponse> request) {
    try {
      return request.get(STUCK_AFTER_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException stuck) {
      throw new IllegalStateException("A request on another thread was never answered", stuck);
    }
  }

  private MockHttpServletResponse changePassword(String authorization) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/password")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                        .formatted(PASSWORD, REPLACEMENT)))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse signOut(String refreshToken) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/sign-out")
                .cookie(new Cookie(RefreshCookie.NAME, refreshToken))
                .header(REQUESTED_WITH, "XMLHttpRequest"))
        .andReturn()
        .getResponse();
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

  private static String bearerOf(MockHttpServletResponse signedIn) throws Exception {
    return "Bearer " + accessTokenOf(signedIn);
  }

  private static String accessTokenOf(MockHttpServletResponse signedIn) throws Exception {
    assertThat(signedIn.getStatus())
        .describedAs("precondition: the account signs in")
        .isEqualTo(200);
    return JsonPath.read(signedIn.getContentAsString(), "$.accessToken");
  }

  private static String refreshTokenOf(MockHttpServletResponse signedIn) {
    String header = signedIn.getHeader(HttpHeaders.SET_COOKIE);
    assertThat(header).describedAs("precondition: a sign-in sets the refresh cookie").isNotNull();
    return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
  }

  /**
   * An operator whose password need not be changed first, so the change is the only thing asked.
   */
  private Account anOperator() {
    Account account =
        new Account(
            UserId.newId(),
            new SignInName("change-after-sign-out-%s@example.com".formatted(UUID.randomUUID())),
            passwords.hash(PASSWORD),
            new OperatorMembership(),
            false,
            Instant.now());
    accounts.save(account);
    return account;
  }
}
