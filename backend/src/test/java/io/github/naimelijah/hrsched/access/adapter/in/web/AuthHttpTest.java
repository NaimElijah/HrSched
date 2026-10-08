package io.github.naimelijah.hrsched.access.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives signing in over HTTP.
 *
 * <p>Most of this file is about what a refusal must not reveal. Four different things can go wrong
 * — a name nobody holds, a wrong password, a disabled account, a locked one — and the caller has to
 * be unable to tell which: each of those, distinguished, is a fact somebody trying to get in would
 * like to have. The tests compare whole responses rather than status codes, because a difference in
 * a header or a body would leak just as well as a different status.
 *
 * <p>The cookie's attributes are asserted one by one off the raw {@code Set-Cookie} header, since
 * each of them is load-bearing (ADR-028) and dropping one would be invisible otherwise.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every password and sign-in name here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthHttpTest {

  private static final String RIGHT_PASSWORD = "an invented password for a test";
  private static final String WRONG_PASSWORD = "not the invented password";

  /**
   * Invented, and shaped for the parser to quote back whole: sent unquoted, it begins like {@code
   * true} and then is not, and Jackson reports that by naming the entire word.
   */
  private static final String BARE_WORD_PASSWORD = "theInventedPasswordForATest";

  /** Set by the trace filter on every response, and the one header that may legitimately differ. */
  private static final String TRACE_ID = "X-Trace-Id";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountRepository accounts;
  @Autowired private PasswordHasher passwords;
  @Autowired private JwtDecoder tokens;
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
  void GivenTheRightPassword_WhenSigningIn_ThenAnAccessTokenAndAHardenedRefreshCookieAreIssued()
      throws Exception {
    Account account = anOperator(RIGHT_PASSWORD);

    MockHttpServletResponse response = signIn(account.signInName().value(), RIGHT_PASSWORD);

    assertThat(response.getStatus()).isEqualTo(200);
    String body = response.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.tokenType")).isEqualTo("Bearer");
    assertThat(JsonPath.<Integer>read(body, "$.expiresIn")).isPositive();
    assertThat(JsonPath.<String>read(body, "$.session.kind")).isEqualTo("OPERATOR");
    assertThat(JsonPath.<String>read(body, "$.session.userId"))
        .isEqualTo(account.id().value().toString());
    assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");

    Jwt token = tokens.decode(JsonPath.read(body, "$.accessToken"));
    assertThat(token.getSubject()).isEqualTo(account.id().value().toString());
    assertThat(token.getClaimAsString("sid")).isNotBlank();

    String cookie = response.getHeader(HttpHeaders.SET_COOKIE);
    assertThat(cookie).isNotNull();
    assertThat(cookie).startsWith("__Secure-hrsched-refresh=");
    assertThat(cookie).contains("HttpOnly");
    assertThat(cookie).contains("Secure");
    assertThat(cookie).contains("SameSite=Strict");
    assertThat(cookie).contains("Path=/api/auth");
    assertThat(cookie).contains("Max-Age=");
    assertThat(cookie)
        .describedAs("the refresh token is never in the body, only in the cookie")
        .doesNotContain(JsonPath.<String>read(body, "$.accessToken"));
  }

  @Test
  void GivenAnUnknownSignInName_WhenSigningIn_ThenTheAnswerIsIdenticalToAWrongPassword()
      throws Exception {
    Account account = anOperator(RIGHT_PASSWORD);

    MockHttpServletResponse wrongPassword = signIn(account.signInName().value(), WRONG_PASSWORD);
    MockHttpServletResponse unknownName =
        signIn("nobody-%s@example.com".formatted(UUID.randomUUID()), WRONG_PASSWORD);

    assertIndistinguishable(wrongPassword, unknownName);
  }

  @Test
  void
      GivenADisabledAccount_WhenSigningInWithTheRightPassword_ThenTheAnswerIsIdenticalToAWrongPassword()
          throws Exception {
    Account active = anOperator(RIGHT_PASSWORD);
    Account disabled = anOperator(RIGHT_PASSWORD);
    accounts.save(disabled.disabled());

    MockHttpServletResponse wrongPassword = signIn(active.signInName().value(), WRONG_PASSWORD);
    MockHttpServletResponse whenDisabled = signIn(disabled.signInName().value(), RIGHT_PASSWORD);

    assertIndistinguishable(wrongPassword, whenDisabled);
  }

  @Test
  void
      GivenALockedAccount_WhenSigningInWithTheRightPassword_ThenTheAnswerIsIdenticalToAWrongPassword()
          throws Exception {
    Account active = anOperator(RIGHT_PASSWORD);
    Account locked = aLockedOperator(RIGHT_PASSWORD);

    MockHttpServletResponse wrongPassword = signIn(active.signInName().value(), WRONG_PASSWORD);
    MockHttpServletResponse whenLocked = signIn(locked.signInName().value(), RIGHT_PASSWORD);

    assertIndistinguishable(wrongPassword, whenLocked);
  }

  /**
   * The reason this use case returns its refusal instead of throwing it. Every use case runs in one
   * transaction that rolls back on an unchecked exception, so a thrown refusal would undo the very
   * attempt it had just counted and nothing would ever lock.
   */
  @Test
  void GivenAWrongPassword_WhenTheRequestIsRefused_ThenTheFailureIsStillCounted() throws Exception {
    Account account = anOperator(RIGHT_PASSWORD);

    signIn(account.signInName().value(), WRONG_PASSWORD);

    assertThat(accounts.findBySignInName(account.signInName()).orElseThrow().failedSignIns())
        .isEqualTo(1);
  }

  @Test
  void GivenThresholdFailures_WhenSigningInWithTheRightPassword_ThenItIsRefused() throws Exception {
    Account account = anOperator(RIGHT_PASSWORD);

    for (int attempt = 0; attempt < lockout.threshold(); attempt++) {
      signIn(account.signInName().value(), WRONG_PASSWORD);
    }

    assertThat(signIn(account.signInName().value(), RIGHT_PASSWORD).getStatus()).isEqualTo(401);
  }

  @Test
  void GivenEarlierFailures_WhenSigningInSuccessfully_ThenTheCountResets() throws Exception {
    Account account = anOperator(RIGHT_PASSWORD);
    signIn(account.signInName().value(), WRONG_PASSWORD);

    assertThat(signIn(account.signInName().value(), RIGHT_PASSWORD).getStatus()).isEqualTo(200);

    assertThat(accounts.findBySignInName(account.signInName()).orElseThrow().failedSignIns())
        .isZero();
  }

  @Test
  void GivenAMixedCaseSignInName_WhenSigningIn_ThenItMatches() throws Exception {
    Account account = anOperator(RIGHT_PASSWORD);

    MockHttpServletResponse response =
        signIn(
            "  " + account.signInName().value().toUpperCase(java.util.Locale.ROOT) + " ",
            RIGHT_PASSWORD);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void GivenSignIns_WhenTheySucceedAndFail_ThenNoLogLineContainsTheSignInNamePasswordOrToken()
      throws Exception {
    Account account = anOperator(RIGHT_PASSWORD);

    MockHttpServletResponse accepted = signIn(account.signInName().value(), RIGHT_PASSWORD);
    signIn(account.signInName().value(), WRONG_PASSWORD);

    String accessToken = JsonPath.read(accepted.getContentAsString(), "$.accessToken");
    assertThat(everythingLogged())
        .doesNotContain(account.signInName().value())
        .doesNotContain(RIGHT_PASSWORD)
        .doesNotContain(WRONG_PASSWORD)
        .doesNotContain(accessToken);
  }

  /**
   * A parser's message quotes what it choked on, and what it choked on is a request body carrying a
   * password. The answer therefore says nothing the parser said.
   */
  @Test
  void
      GivenAMalformedBodyContainingAPassword_WhenSigningIn_ThenItIsABadRequestAndTheLogDoesNotContainIt()
          throws Exception {
    String malformed =
        "{\"signInName\":\"ada@example.com\",\"password\":\"%s\"".formatted(RIGHT_PASSWORD);

    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/auth/sign-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(malformed))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse();

    assertThat(response.getContentAsString()).doesNotContain(RIGHT_PASSWORD);
    assertThat(everythingLogged()).doesNotContain(RIGHT_PASSWORD);
  }

  /**
   * {@code GivenAMalformedBodyContainingAPassword_…} cannot catch an answer or a log line that
   * repeats the parser: its body is truncated, and a message about running out of input quotes
   * nothing. A password sent as a bare word is what the parser does quote, so this is the request
   * that would carry it back to the caller, or into the log.
   */
  @Test
  void GivenAPasswordTheParserQuotesBack_WhenSigningIn_ThenNeitherTheAnswerNorTheLogRepeatsIt()
      throws Exception {
    String unquoted =
        "{\"signInName\":\"ada@example.com\",\"password\":%s}".formatted(BARE_WORD_PASSWORD);

    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/auth/sign-in").contentType(MediaType.APPLICATION_JSON).content(unquoted))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse();

    assertThat(response.getContentAsString()).doesNotContain(BARE_WORD_PASSWORD);
    assertThat(everythingLogged()).doesNotContain(BARE_WORD_PASSWORD);
  }

  @Test
  void GivenANonJsonBody_WhenSigningIn_ThenItIsRefusedWithoutSigningIn() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/sign-in")
                .contentType(MediaType.TEXT_PLAIN)
                .content("ada:" + RIGHT_PASSWORD))
        .andExpect(status().isUnsupportedMediaType());

    assertThat(everythingLogged()).doesNotContain(RIGHT_PASSWORD);
  }

  private MockHttpServletResponse signIn(String name, String password) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/sign-in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"signInName\":%s,\"password\":%s}"
                        .formatted(quoted(name), quoted(password))))
        .andReturn()
        .getResponse();
  }

  /**
   * Compares two refusals in full. Only the trace id may differ; anything else that did would be
   * something a caller could use to tell the two situations apart.
   */
  private static void assertIndistinguishable(
      MockHttpServletResponse one, MockHttpServletResponse other) throws Exception {
    assertThat(other.getStatus()).isEqualTo(one.getStatus());
    assertThat(other.getContentAsString()).isEqualTo(one.getContentAsString());
    assertThat(headersOf(other)).isEqualTo(headersOf(one));
  }

  private static List<String> headersOf(MockHttpServletResponse response) {
    return response.getHeaderNames().stream()
        .filter(name -> !TRACE_ID.equalsIgnoreCase(name))
        .sorted()
        .map(name -> name + "=" + response.getHeaderValues(name))
        .toList();
  }

  private Account anOperator(String password) {
    Account account =
        new Account(
            UserId.newId(),
            new SignInName("operator-%s@example.com".formatted(UUID.randomUUID())),
            passwords.hash(password),
            new OperatorMembership(),
            false,
            Instant.now());
    accounts.save(account);
    return account;
  }

  /** Locked well into the future, so the lock is a fact of the row rather than of the clock. */
  private Account aLockedOperator(String password) {
    Account account =
        new Account(
            UserId.newId(),
            new SignInName("locked-%s@example.com".formatted(UUID.randomUUID())),
            passwords.hash(password),
            new OperatorMembership(),
            true,
            false,
            lockout.threshold(),
            Optional.of(Instant.now().plus(Duration.ofDays(1))),
            Instant.now());
    accounts.save(account);
    return account;
  }

  private String everythingLogged() {
    return captured.list.stream()
        .map(
            event ->
                event.getFormattedMessage()
                    + " "
                    + (event.getKeyValuePairs() == null ? "" : event.getKeyValuePairs()))
        .reduce("", (all, line) -> all + "\n" + line);
  }

  private static String quoted(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
