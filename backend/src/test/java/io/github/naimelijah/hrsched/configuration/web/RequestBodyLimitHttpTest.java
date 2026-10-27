package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Specification of the ceiling on a JSON request body (issue #250).
 *
 * <p>Without one, Jackson 3's own defaults accept a string of a hundred million characters and a
 * document of any length, so anybody, with no token, could make {@code POST /api/auth/sign-in}
 * buffer and then hash whatever they sent. A body over the configured maximum is refused as a
 * malformed request before any use case runs; a body at or under it is answered exactly as before.
 *
 * <p>Sign-in is the route because it is the one open to everybody. The limit is read from {@code
 * hrsched.web.max-json-document-bytes} rather than written here, so the test follows the
 * configuration instead of restating it. Every body is ASCII, so its length in characters is its
 * length in bytes.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every sign-in name and password here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class RequestBodyLimitHttpTest {

  /** Invented, and long enough for the password policy. */
  private static final String PASSWORD = "an invented password for a body-limit test";

  /** Invented, and never the password of any account. */
  private static final String WRONG_PASSWORD_CHARACTER = "x";

  private static final String SIGN_IN = "/api/auth/sign-in";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountRepository accounts;
  @Autowired private PasswordHasher passwords;

  @Value("${hrsched.web.max-json-document-bytes}")
  private int maximumDocumentBytes;

  @Test
  void GivenABodyOneByteOverTheLimit_WhenSigningIn_ThenItIsABadRequestWithAProblemDetail()
      throws Exception {
    MockHttpServletResponse response =
        signIn(bodyOfLength(anInventedName(), maximumDocumentBytes + 1));

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType())
        .describedAs("the refusal carries a problem detail")
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
  }

  /**
   * The persisted proof that the refusal came before the use case: signing in with a wrong password
   * counts a failed attempt against the account, and an oversized body must not get that far.
   */
  @Test
  void GivenABodyOneByteOverTheLimitNamingARealAccount_WhenSigningIn_ThenNoFailedAttemptIsCounted()
      throws Exception {
    SignInName name = anAccount();

    signIn(bodyOfLength(name.value(), maximumDocumentBytes + 1));

    assertThat(accounts.findBySignInName(name).orElseThrow().failedSignIns())
        .describedAs("failed sign-ins after an oversized body was refused")
        .isZero();
  }

  /** The boundary itself: the limit is a maximum, so a body of exactly that length is accepted. */
  @Test
  void GivenABodyExactlyAtTheLimit_WhenSigningInWithAWrongPassword_ThenItIsTheUsualRefusal()
      throws Exception {
    MockHttpServletResponse response = signIn(bodyOfLength(anInventedName(), maximumDocumentBytes));

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void GivenAnOrdinaryBody_WhenSigningInWithTheRightPassword_ThenItIsSignedIn() throws Exception {
    SignInName name = anAccount();

    MockHttpServletResponse response =
        signIn("{\"signInName\":\"%s\",\"password\":\"%s\"}".formatted(name.value(), PASSWORD));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  /**
   * A sign-in body of exactly {@code length} bytes, padded out with a wrong password.
   *
   * @param signInName the name to sign in as
   * @param length the length of the whole document, in bytes
   */
  private static String bodyOfLength(String signInName, int length) {
    String prefix = "{\"signInName\":\"%s\",\"password\":\"".formatted(signInName);
    String suffix = "\"}";
    String body =
        prefix
            + WRONG_PASSWORD_CHARACTER.repeat(length - prefix.length() - suffix.length())
            + suffix;
    assertThat(body.getBytes(StandardCharsets.UTF_8))
        .describedAs("arranged: the body's length in bytes")
        .hasSize(length);
    return body;
  }

  private MockHttpServletResponse signIn(String body) throws Exception {
    return mockMvc
        .perform(post(SIGN_IN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse();
  }

  private static String anInventedName() {
    return "body-limit-nobody-%s@example.com".formatted(UUID.randomUUID());
  }

  /** A fresh operator account nothing else signs in as, so its failure count starts at zero. */
  private SignInName anAccount() {
    SignInName name = new SignInName("body-limit-%s@example.com".formatted(UUID.randomUUID()));
    accounts.saveIfAbsent(
        new Account(
            UserId.newId(),
            name,
            passwords.hash(PASSWORD),
            new OperatorMembership(),
            false,
            Instant.now()));
    return name;
  }
}
