package io.github.naimelijah.hrsched.access.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.access.adapter.out.token.AccessTokenKey;
import io.github.naimelijah.hrsched.access.adapter.out.token.AccessTokenProperties;
import io.github.naimelijah.hrsched.access.adapter.out.token.JwtAccessTokenIssuer;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Drives renewing and ending a session over HTTP.
 *
 * <p>The reuse tests are the point of the file. A refresh token works exactly once, so a token
 * presented twice means two parties hold it and there is no way to tell which of them is its owner
 * — the only safe answer is to stop believing both, and that revocation has to survive the refusal
 * that follows it. That it commits is asserted by reading the session row back after the 401.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every password and sign-in name here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class SessionRefreshHttpTest {

  private static final String PASSWORD = "an invented password for a test";
  private static final String REQUESTED_WITH = "X-Requested-With";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountRepository accounts;
  @Autowired private PasswordHasher passwords;
  @Autowired private JwtDecoder tokens;
  @Autowired private JwtEncoder encoder;
  @Autowired private AccessTokenKey key;
  @Autowired private AccessTokenProperties tokenProperties;

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
  void GivenAValidRefreshCookie_WhenRefreshing_ThenANewAccessTokenAndARotatedCookieAreIssued()
      throws Exception {
    MockHttpServletResponse signedIn = signIn(anOperator());
    String first = refreshTokenOf(signedIn);

    MockHttpServletResponse renewed = refresh(first);

    assertThat(renewed.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<String>read(renewed.getContentAsString(), "$.accessToken")).isNotBlank();
    assertThat(refreshTokenOf(renewed))
        .describedAs("a refresh token works exactly once, so renewing must hand back a new one")
        .isNotEqualTo(first);
  }

  @Test
  void GivenARotatedRefreshToken_WhenPresentedAgain_ThenTheWholeSessionIsRevoked()
      throws Exception {
    MockHttpServletResponse signedIn = signIn(anOperator());
    String first = refreshTokenOf(signedIn);
    String successor = refreshTokenOf(refresh(first));

    assertThat(refresh(first).getStatus()).isEqualTo(401);

    assertThat(refresh(successor).getStatus())
        .describedAs("the session ended, so the token that was still current is no good either")
        .isEqualTo(401);
  }

  @Test
  void GivenReuseIsDetected_WhenTheRequestIsRefused_ThenTheRevocationIsStillCommitted()
      throws Exception {
    MockHttpServletResponse signedIn = signIn(anOperator());
    UUID session = sessionOf(signedIn);
    String first = refreshTokenOf(signedIn);
    refresh(first);

    assertThat(refresh(first).getStatus()).isEqualTo(401);

    assertThat(revocationOf(session))
        .describedAs("a refusal that rolled back its own revocation would undo the one defence")
        .isEqualTo("REFRESH_REUSED");
  }

  @Test
  void GivenAnUnknownRefreshToken_WhenRefreshing_ThenItIsRefusedAndNothingIsRevoked()
      throws Exception {
    UUID session = sessionOf(signIn(anOperator()));

    assertThat(refresh("a-token-nobody-ever-issued").getStatus()).isEqualTo(401);

    assertThat(revocationOf(session))
        .describedAs("a token nobody issued says nothing about a session that is still good")
        .isNull();
  }

  @Test
  void GivenNoRefreshCookie_WhenRefreshing_ThenItIsUnauthorised() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(post("/api/auth/refresh").header(REQUESTED_WITH, "XMLHttpRequest"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
  }

  @Test
  void GivenAnExpiredSession_WhenRefreshing_ThenItIsRefused() throws Exception {
    MockHttpServletResponse signedIn = signIn(anOperator());
    String token = refreshTokenOf(signedIn);
    endSessionAt(sessionOf(signedIn), Instant.now().minusSeconds(1));

    assertThat(refresh(token).getStatus()).isEqualTo(401);
  }

  @Test
  void GivenADisabledAccount_WhenRefreshing_ThenItIsRefused() throws Exception {
    Account account = anOperator();
    String token = refreshTokenOf(signIn(account));
    accounts.save(accounts.findForAuthentication(account.id()).orElseThrow().disabled());

    assertThat(refresh(token).getStatus()).isEqualTo(401);
  }

  @Test
  void GivenASignedOutSession_WhenRefreshingWithTheSameCookie_ThenItIsRefused() throws Exception {
    String token = refreshTokenOf(signIn(anOperator()));

    assertThat(signOut(token).getStatus()).isEqualTo(204);

    assertThat(refresh(token).getStatus()).isEqualTo(401);
  }

  @Test
  void GivenNoRequestedWithHeader_WhenRefreshing_ThenItIsABadRequestNamingIt() throws Exception {
    String token = refreshTokenOf(signIn(anOperator()));

    MockHttpServletResponse response =
        mockMvc
            .perform(post("/api/auth/refresh").cookie(refreshCookie(token)))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains(REQUESTED_WITH);
  }

  @Test
  void GivenNoRequestedWithHeader_WhenSigningOut_ThenItIsABadRequestNamingIt() throws Exception {
    String token = refreshTokenOf(signIn(anOperator()));

    MockHttpServletResponse response =
        mockMvc
            .perform(post("/api/auth/sign-out").cookie(refreshCookie(token)))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains(REQUESTED_WITH);
  }

  @Test
  void GivenNoCookie_WhenSigningOut_ThenItStillAnswersNoContentAndClearsTheCookie()
      throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(post("/api/auth/sign-out").header(REQUESTED_WITH, "XMLHttpRequest"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
  }

  @Test
  void GivenARefusedRefresh_WhenAnswered_ThenTheCookieIsCleared() throws Exception {
    MockHttpServletResponse response = refresh("a-token-nobody-ever-issued");

    assertThat(response.getStatus()).isEqualTo(401);
    String cookie = response.getHeader(HttpHeaders.SET_COOKIE);
    assertThat(cookie).isNotNull();
    assertThat(cookie).startsWith("__Secure-hrsched-refresh=;");
    assertThat(cookie).contains("Max-Age=0");
  }

  /**
   * The premise of #179, pinned on the backend side: the bearer filter refuses a stale access token
   * before the controller reads the cookie, even on a route that asks for no token. A client that
   * renews only after its token has expired must therefore send no token with the renewal.
   *
   * <p>The refusal is the filter's and not the controller's: no {@code Set-Cookie} clears the
   * cookie, and the same cookie still renews once the token is left off.
   */
  @Test
  void
      GivenAnExpiredAccessTokenBesideAValidCookie_WhenRefreshing_ThenTheTokenIsRefusedAndTheCookieUntouched()
          throws Exception {
    Account account = anOperator();
    MockHttpServletResponse signedIn = signIn(account);
    String cookie = refreshTokenOf(signedIn);
    String stale = anExpiredAccessToken(account, sessionOf(signedIn));

    MockHttpServletResponse refused =
        mockMvc
            .perform(
                withCookie(post("/api/auth/refresh"), cookie)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + stale))
            .andReturn()
            .getResponse();

    assertThat(refused.getStatus()).isEqualTo(401);
    assertThat(JsonPath.<String>read(refused.getContentAsString(), "$.title"))
        .isEqualTo("Unauthenticated");
    assertThat(refused.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    assertThat(refresh(cookie).getStatus())
        .describedAs("the same cookie, sent without the stale token, is still good")
        .isEqualTo(200);
  }

  /**
   * Signing out after idle is the case that matters on a shared machine: refused this way, the
   * session is not revoked and the cookie survives, so a reload signs the person back in (#179).
   */
  @Test
  void
      GivenAnExpiredAccessTokenBesideAValidCookie_WhenSigningOut_ThenItIsRefusedAndTheSessionSurvives()
          throws Exception {
    Account account = anOperator();
    MockHttpServletResponse signedIn = signIn(account);
    UUID session = sessionOf(signedIn);
    String stale = anExpiredAccessToken(account, session);

    MockHttpServletResponse refused =
        mockMvc
            .perform(
                withCookie(post("/api/auth/sign-out"), refreshTokenOf(signedIn))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + stale))
            .andReturn()
            .getResponse();

    assertThat(refused.getStatus()).isEqualTo(401);
    assertThat(refused.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    assertThat(revocationOf(session)).isNull();
  }

  @Test
  void GivenAValidCookieAndNoAccessToken_WhenSigningOut_ThenTheSessionIsRevoked() throws Exception {
    MockHttpServletResponse signedIn = signIn(anOperator());
    UUID session = sessionOf(signedIn);

    assertThat(signOut(refreshTokenOf(signedIn)).getStatus()).isEqualTo(204);

    assertThat(revocationOf(session)).isNotNull();
  }

  @Test
  void GivenRefreshesAndSignOuts_WhenAnswered_ThenNoTokenValueIsEverLogged() throws Exception {
    String token = refreshTokenOf(signIn(anOperator()));
    String successor = refreshTokenOf(refresh(token));
    refresh(token);
    signOut(successor);

    assertThat(everythingLogged()).doesNotContain(token).doesNotContain(successor);
  }

  private MockHttpServletResponse signIn(Account account) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/sign-in")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"signInName\":\"%s\",\"password\":\"%s\"}"
                        .formatted(account.signInName().value(), PASSWORD)))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse refresh(String refreshToken) throws Exception {
    return mockMvc
        .perform(withCookie(post("/api/auth/refresh"), refreshToken))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse signOut(String refreshToken) throws Exception {
    return mockMvc
        .perform(withCookie(post("/api/auth/sign-out"), refreshToken))
        .andReturn()
        .getResponse();
  }

  private static MockHttpServletRequestBuilder withCookie(
      MockHttpServletRequestBuilder request, String refreshToken) {
    return request.cookie(refreshCookie(refreshToken)).header(REQUESTED_WITH, "XMLHttpRequest");
  }

  private static Cookie refreshCookie(String value) {
    return new Cookie("__Secure-hrsched-refresh", value);
  }

  /** Reads the token straight off the {@code Set-Cookie} header, as a browser would. */
  private static String refreshTokenOf(MockHttpServletResponse response) {
    String header = response.getHeader(HttpHeaders.SET_COOKIE);
    assertThat(header).isNotNull();
    String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    assertThat(value).isNotBlank();
    return value;
  }

  private Account anOperator() {
    Account account =
        new Account(
            UserId.newId(),
            new SignInName("operator-%s@example.com".formatted(UUID.randomUUID())),
            passwords.hash(PASSWORD),
            new OperatorMembership(),
            false,
            Instant.now());
    accounts.save(account);
    return account;
  }

  /**
   * The session a response's access token names. The HTTP tests share one database and do not empty
   * it, so every query below is about one named session rather than about whatever rows exist.
   */
  private UUID sessionOf(MockHttpServletResponse response) throws Exception {
    String accessToken = JsonPath.read(response.getContentAsString(), "$.accessToken");
    return UUID.fromString(tokens.decode(accessToken).getClaimAsString("sid"));
  }

  /**
   * A token this application signed for a real account and session, issued an hour ago so that it
   * has expired by more than the decoder's clock skew. Only its age is wrong with it.
   */
  private String anExpiredAccessToken(Account account, UUID session) {
    Clock anHourAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
    return new JwtAccessTokenIssuer(encoder, key, tokenProperties, anHourAgo)
        .issue(account.id(), new SignInSessionId(session))
        .value();
  }

  private static String revocationOf(UUID session) {
    return PostgresDatabase.jdbcClient()
        .sql("SELECT revocation FROM sign_in_session WHERE id = :id")
        .param("id", session)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /**
   * Moves a session wholly into the past. Both ends, because the schema refuses a session that
   * would end before it began - which is the check earning its keep on a test that tried to.
   */
  private static void endSessionAt(UUID session, Instant when) {
    PostgresDatabase.jdbcClient()
        .sql("UPDATE sign_in_session SET started_at = :started, expires_at = :ended WHERE id = :id")
        .param("started", OffsetDateTime.ofInstant(when.minusSeconds(3600), ZoneOffset.UTC))
        .param("ended", OffsetDateTime.ofInstant(when, ZoneOffset.UTC))
        .param("id", session)
        .update();
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
}
