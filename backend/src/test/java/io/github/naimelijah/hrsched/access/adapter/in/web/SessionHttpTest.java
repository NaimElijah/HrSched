package io.github.naimelijah.hrsched.access.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.LockoutPolicy;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives reading one's own session and changing one's own password, and every way a token stops
 * being believed.
 *
 * <p>Most of this file exists because a signature is not enough. A token that was genuinely issued
 * by this service, signed correctly and not yet expired, must still be refused once the account is
 * disabled, the session signed out, the session killed by reuse detection, or the password changed.
 * Each of those is a separate test here, because each is a separate way somebody keeps access they
 * should have lost — and none of them is visible to the decoder.
 *
 * <p>The crafted token pairing one account's subject with another account's session is the sharpest
 * of them: it is signed by this service and every claim in it is real, and it must still be
 * refused.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every password and sign-in name here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class SessionHttpTest {

  private static final String PASSWORD = "an invented password for a test";
  private static final String ANOTHER_PASSWORD = "a different invented password";
  private static final String REQUESTED_WITH = "X-Requested-With";

  /** Invented, and set to one so a single failure is enough to arrange a locked account. */
  private static final LockoutPolicy LOCK_ON_THE_FIRST_FAILURE =
      new LockoutPolicy(1, Duration.ofMinutes(15));

  private static final String SESSION = "/api/auth/session";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountRepository accounts;
  @Autowired private PasswordHasher passwords;
  @Autowired private JwtEncoder encoder;
  @Autowired private JwtDecoder decoder;

  @Test
  void
      GivenNoToken_WhenReadingTheSession_ThenItIsUnauthorisedWithAProblemDetailAndABearerChallenge()
          throws Exception {
    MockHttpServletResponse response = mockMvc.perform(get(SESSION)).andReturn().getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    assertThat(response.getContentType()).contains("application/problem+json");
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.title"))
        .isEqualTo("Unauthenticated");
  }

  @Test
  void GivenNoToken_WhenReadingTheSession_ThenTheRefusalStillCarriesATraceId() throws Exception {
    MockHttpServletResponse response = mockMvc.perform(get(SESSION)).andReturn().getResponse();

    assertThat(response.getHeader("X-Trace-Id"))
        .describedAs("a refusal a caller cannot quote is a refusal nobody can look up")
        .isNotBlank();
  }

  @Test
  void GivenAMalformedAuthorizationHeader_WhenReadingTheSession_ThenItIsUnauthorised()
      throws Exception {
    assertThat(readSessionWith("Bearer not-a-token").getStatus()).isEqualTo(401);
    assertThat(readSessionWith("Basic bm90OmEtdG9rZW4=").getStatus()).isEqualTo(401);
  }

  @Test
  void GivenAValidToken_WhenReadingTheSession_ThenTheCallersAccountIsDescribed() throws Exception {
    Account operator = anOperator(PASSWORD, false);

    MockHttpServletResponse response = readSessionWith(bearerFor(operator));

    assertThat(response.getStatus()).isEqualTo(200);
    String body = response.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.userId")).isEqualTo(operator.id().value().toString());
    assertThat(JsonPath.<String>read(body, "$.kind")).isEqualTo("OPERATOR");
    assertThat(JsonPath.<Boolean>read(body, "$.passwordChangeRequired")).isFalse();
  }

  @Test
  void GivenATenantsToken_WhenReadingTheSession_ThenTheirOrganisationAndRoleAreDescribed()
      throws Exception {
    OrganisationId acme = anOrganisation();
    Account admin = aTenant(acme, Role.ADMIN, PASSWORD, false);

    String body = readSessionWith(bearerFor(admin)).getContentAsString();

    assertThat(JsonPath.<String>read(body, "$.kind")).isEqualTo("TENANT");
    assertThat(JsonPath.<String>read(body, "$.organisation")).isEqualTo(acme.value().toString());
    assertThat(JsonPath.<String>read(body, "$.role")).isEqualTo("ADMIN");
  }

  @Test
  void GivenATokenForADisabledAccount_WhenReadingTheSession_ThenItIsUnauthorised()
      throws Exception {
    Account operator = anOperator(PASSWORD, false);
    String token = bearerFor(operator);
    accounts.save(accounts.findForAuthentication(operator.id()).orElseThrow().disabled());

    assertThat(readSessionWith(token).getStatus())
        .describedAs("the token is still valid; the account is not")
        .isEqualTo(401);
  }

  /**
   * A lockout stops a sign-in, and nothing else.
   *
   * <p>An account is locked because somebody is guessing at its password, and the person guessing
   * is not the person holding this session. Ending it would turn an attack on somebody into an
   * outage for them, and would hand anybody a way to sign a colleague out by mistyping their
   * password ten times. {@code SessionService.findActor} therefore does not filter on the lock, and
   * this is the test that stops somebody adding the filter believing it to be a tightening.
   */
  @Test
  void GivenATokenForALockedAccount_WhenReadingTheSession_ThenTheSessionSurvivesTheLock()
      throws Exception {
    Account operator = anOperator(PASSWORD, false);
    String token = bearerFor(operator);
    Account stored = accounts.findForAuthentication(operator.id()).orElseThrow();
    accounts.save(stored.recordFailedSignIn(Instant.now(), LOCK_ON_THE_FIRST_FAILURE));

    assertThat(readSessionWith(token).getStatus())
        .describedAs("the session they already hold")
        .isEqualTo(200);
    assertThat(signIn(operator, PASSWORD).getStatus())
        .describedAs("the sign-in the lock is actually for")
        .isEqualTo(401);
  }

  @Test
  void GivenATokenFromASignedOutSession_WhenReadingTheSession_ThenItIsUnauthorised()
      throws Exception {
    Account operator = anOperator(PASSWORD, false);
    MockHttpServletResponse signedIn = signIn(operator, PASSWORD);
    String token = "Bearer " + accessTokenOf(signedIn);
    signOut(refreshTokenOf(signedIn));

    assertThat(readSessionWith(token).getStatus()).isEqualTo(401);
  }

  @Test
  void GivenATokenFromASessionEndedByReuse_WhenReadingTheSession_ThenItIsUnauthorised()
      throws Exception {
    Account operator = anOperator(PASSWORD, false);
    MockHttpServletResponse signedIn = signIn(operator, PASSWORD);
    String token = "Bearer " + accessTokenOf(signedIn);
    String first = refreshTokenOf(signedIn);
    refresh(first);
    refresh(first);

    assertThat(readSessionWith(token).getStatus()).isEqualTo(401);
  }

  /**
   * A token signed by this service, with a real account and a real session — belonging to two
   * different people. The query asks for both at once, so it matches nothing.
   */
  @Test
  void GivenATokenForAnotherAccountsSession_WhenReadingTheSession_ThenItIsUnauthorised()
      throws Exception {
    Account mine = anOperator(PASSWORD, false);
    Account theirs = anOperator(PASSWORD, false);
    UUID theirSession = sessionOf(signIn(theirs, PASSWORD));

    String crafted = craftToken(mine.id().value().toString(), theirSession.toString());

    assertThat(readSessionWith("Bearer " + crafted).getStatus()).isEqualTo(401);
  }

  @Test
  void GivenATemporaryPassword_WhenReadingTheSession_ThenAChangeIsReportedRequired()
      throws Exception {
    Account operator = anOperator(PASSWORD, true);

    String body = readSessionWith(bearerFor(operator)).getContentAsString();

    assertThat(JsonPath.<Boolean>read(body, "$.passwordChangeRequired")).isTrue();
  }

  @Test
  void
      GivenATemporaryPassword_WhenChangingIt_ThenANewSessionIsIssuedAndTheChangeIsNoLongerRequired()
          throws Exception {
    Account operator = anOperator(PASSWORD, true);

    MockHttpServletResponse changed =
        changePassword(bearerFor(operator), PASSWORD, ANOTHER_PASSWORD);

    assertThat(changed.getStatus()).isEqualTo(200);
    assertThat(
            JsonPath.<Boolean>read(
                changed.getContentAsString(), "$.session.passwordChangeRequired"))
        .isFalse();
    assertThat(readSessionWith("Bearer " + accessTokenOf(changed)).getStatus()).isEqualTo(200);
  }

  @Test
  void GivenATemporaryPassword_WhenChangingItToTheSameValue_ThenItIsRefused() throws Exception {
    Account operator = anOperator(PASSWORD, true);

    MockHttpServletResponse response = changePassword(bearerFor(operator), PASSWORD, PASSWORD);

    assertThat(response.getStatus()).isEqualTo(422);
    assertThat(JsonPath.<String>read(response.getContentAsString(), "$.reason"))
        .isEqualTo("SAME_AS_CURRENT");
  }

  @Test
  void GivenAShortNewPassword_WhenChangingIt_ThenItIsRefusedNamingTheRule() throws Exception {
    Account operator = anOperator(PASSWORD, false);

    MockHttpServletResponse response = changePassword(bearerFor(operator), PASSWORD, "short");

    assertThat(response.getStatus()).isEqualTo(422);
    String body = response.getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.reason")).isEqualTo("TOO_SHORT");
    assertThat(body).doesNotContain("short");
  }

  @Test
  void GivenAWrongCurrentPassword_WhenChangingIt_ThenItIsRefusedAndTheFailureIsCounted()
      throws Exception {
    Account operator = anOperator(PASSWORD, false);

    MockHttpServletResponse response =
        changePassword(bearerFor(operator), "not the password", ANOTHER_PASSWORD);

    assertThat(response.getStatus())
        .describedAs("403 rather than 401: a 401 would send the client off to renew and retry")
        .isEqualTo(403);
    assertThat(accounts.findForAuthentication(operator.id()).orElseThrow().failedSignIns())
        .isEqualTo(1);
  }

  @Test
  void GivenAPasswordChange_WhenAnotherSessionUsesItsTokens_ThenTheyAreRefused() throws Exception {
    Account operator = anOperator(PASSWORD, false);
    MockHttpServletResponse elsewhere = signIn(operator, PASSWORD);
    String otherAccessToken = "Bearer " + accessTokenOf(elsewhere);
    String otherRefreshToken = refreshTokenOf(elsewhere);

    changePassword(bearerFor(operator), PASSWORD, ANOTHER_PASSWORD);

    assertThat(readSessionWith(otherAccessToken).getStatus()).isEqualTo(401);
    assertThat(refresh(otherRefreshToken).getStatus()).isEqualTo(401);
  }

  /**
   * An encoded path prefix. Recorded rather than assumed: Spring's firewall refuses a URL-encoded
   * path outright, so this never reaches a rule at all — which is a safe answer, but not the same
   * answer as the rule giving, and worth knowing.
   */
  @Test
  void GivenAnEncodedPathPrefix_WhenReadingTheSessionWithoutAToken_ThenItIsStillRefused()
      throws Exception {
    MockHttpServletResponse response =
        mockMvc.perform(get("/%61pi/auth/session")).andReturn().getResponse();

    assertThat(response.getStatus())
        .describedAs("whatever refuses it, an encoded path must not reach the endpoint")
        .isNotEqualTo(200);
  }

  /**
   * Documents the bearer filter's own behaviour, which the SPA has to respect: a token that fails
   * verification is refused on <em>every</em> route, including one that asks for no authentication.
   * A client must therefore send no token rather than a stale one.
   */
  @Test
  void GivenAnInvalidBearerToken_WhenCallingARouteThatStillTakesHeaders_ThenItIsUnauthorised()
      throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/organisations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
                    .header("X-User", UUID.randomUUID().toString())
                    .header("X-Role", "OPERATOR")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TestTenants.onboarding("Acme %s".formatted(UUID.randomUUID()))))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(401);
  }

  /** Proves this task locked nothing but the two endpoints it was meant to. */
  @Test
  void GivenAHeaderIdentityRoute_WhenCalledWithoutAToken_ThenItStillWorks() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                    .content(TestTenants.onboarding("Acme %s".formatted(UUID.randomUUID()))))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(201);
  }

  private MockHttpServletResponse readSessionWith(String authorization) throws Exception {
    return mockMvc
        .perform(get(SESSION).header(HttpHeaders.AUTHORIZATION, authorization))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse changePassword(
      String authorization, String current, String replacement) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/password")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}"
                        .formatted(current, replacement)))
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

  private MockHttpServletResponse refresh(String refreshToken) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/refresh")
                .cookie(new Cookie(RefreshCookie.NAME, refreshToken))
                .header(REQUESTED_WITH, "XMLHttpRequest"))
        .andReturn()
        .getResponse();
  }

  private void signOut(String refreshToken) throws Exception {
    mockMvc.perform(
        post("/api/auth/sign-out")
            .cookie(new Cookie(RefreshCookie.NAME, refreshToken))
            .header(REQUESTED_WITH, "XMLHttpRequest"));
  }

  private String bearerFor(Account account) throws Exception {
    return "Bearer " + accessTokenOf(signIn(account, PASSWORD));
  }

  private static String accessTokenOf(MockHttpServletResponse response) throws Exception {
    return JsonPath.read(response.getContentAsString(), "$.accessToken");
  }

  private static String refreshTokenOf(MockHttpServletResponse response) {
    String header = response.getHeader(HttpHeaders.SET_COOKIE);
    assertThat(header).isNotNull();
    return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
  }

  /** The session a response's own access token names, rather than whatever row is newest. */
  private UUID sessionOf(MockHttpServletResponse response) throws Exception {
    return UUID.fromString(decoder.decode(accessTokenOf(response)).getClaimAsString("sid"));
  }

  /**
   * Signs a token this service would accept the signature of, with whatever claims the test wants.
   * Uses the application's own encoder, so nothing here reimplements how a token is made.
   */
  private String craftToken(String subject, String session) {
    Instant now = Instant.now();
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).type("at+jwt").build(),
                JwtClaimsSet.builder()
                    .issuer("hrsched")
                    .audience(List.of("hrsched-api"))
                    .subject(subject)
                    .issuedAt(now)
                    .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                    .claim("sid", session)
                    .build()))
        .getTokenValue();
  }

  private OrganisationId anOrganisation() {
    OrganisationId organisation = OrganisationId.newId();
    PostgresDatabase.given(organisation);
    return organisation;
  }

  private Account anOperator(String password, boolean passwordChangeRequired) {
    return store(
        new Account(
            UserId.newId(),
            new SignInName("operator-%s@example.com".formatted(UUID.randomUUID())),
            passwords.hash(password),
            new OperatorMembership(),
            passwordChangeRequired,
            Instant.now()));
  }

  private Account aTenant(
      OrganisationId organisation, Role role, String password, boolean passwordChangeRequired) {
    return store(
        new Account(
            UserId.newId(),
            new SignInName("member-%s@example.com".formatted(UUID.randomUUID())),
            passwords.hash(password),
            new TenantMembership(organisation, role, Optional.empty()),
            passwordChangeRequired,
            Instant.now()));
  }

  private Account store(Account account) {
    accounts.save(account);
    return account;
  }
}
