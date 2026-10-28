package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Specification of the ceiling on a JSON request body (issue #250).
 *
 * <p>Without one, Jackson 3's own defaults accept a string of a hundred million characters and a
 * document of any length, so anybody, with no token, could make {@code POST /api/auth/sign-in}
 * buffer and then hash whatever they sent. A body over the configured maximum is refused before any
 * use case runs, as RFC 9110 section 15.5.14 says: a 413, titled {@code Request too large}, whose
 * detail repeats nothing that was sent. It is not a malformed request, and saying it was sends a
 * client looking for a fault in JSON that has none. A body at or under the maximum is answered
 * exactly as before.
 *
 * <p>Sign-in is the route that matters most, because it is open to everybody. Each context has its
 * own exception handler (ADR-024), so one write per context is refused as well: replacing the
 * constraint set for scheduling, and adding an employee for workforce. Their bodies are valid
 * documents padded with whitespace before the closing brace, so the padding changes nothing but the
 * length, and a body padded to the limit exactly is accepted; only the length can refuse one a byte
 * longer.
 *
 * <p>The limit is read from {@code hrsched.web.max-json-document-bytes} rather than written here,
 * so the test follows the configuration instead of restating it. It is a number of bytes, so one
 * body is made of characters that take two bytes each in UTF-8, and is over the limit only when
 * counted in bytes.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every sign-in name, password, employee name and constraint value here is invented for the
 * test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class RequestBodyLimitHttpTest {

  /** Invented, and long enough for the password policy. */
  private static final String PASSWORD = "an invented password for a body-limit test";

  /** Invented, and never the password of any account. */
  private static final String WRONG_PASSWORD_CHARACTER = "x";

  /** Invented: one character that UTF-8 writes in two bytes. */
  private static final String TWO_BYTE_CHARACTER = "é";

  /** RFC 9110's status for a body larger than the server is willing to process. */
  private static final int CONTENT_TOO_LARGE = 413;

  private static final String TOO_LARGE_TITLE = "Request too large";

  /** Invented: a cap in minutes, chosen only so the constraint set is valid. */
  private static final String INVENTED_CONSTRAINT_SET = "{\"maximumDailyMinutes\":60";

  /** A field the constraint set's body names, which the refusal must not repeat. */
  private static final String CONSTRAINT_FIELD = "maximumDailyMinutes";

  private static final String SIGN_IN = "/api/auth/sign-in";
  private static final String CONSTRAINTS = "/api/constraints";
  private static final String EMPLOYEES = "/api/employees";

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountRepository accounts;
  @Autowired private PasswordHasher passwords;
  @Autowired private Clock clock;

  @Value("${hrsched.web.max-json-document-bytes}")
  private int maximumDocumentBytes;

  @Test
  void GivenABodyOneByteOverTheLimit_WhenSigningIn_ThenItIsRefusedAsTooLarge() throws Exception {
    String name = anInventedName();

    MockHttpServletResponse response = signIn(bodyOfLength(name, maximumDocumentBytes + 1));

    assertRefusedAsTooLarge(response, name);
  }

  /**
   * The persisted proof that the refusal came before the use case: signing in with a wrong password
   * counts a failed attempt against the account, and an oversized body must not get that far.
   */
  @Test
  void GivenABodyOneByteOverTheLimitNamingARealAccount_WhenSigningIn_ThenNoFailedAttemptIsCounted()
      throws Exception {
    SignInName name = anAccount();

    MockHttpServletResponse response = signIn(bodyOfLength(name.value(), maximumDocumentBytes + 1));

    assertThat(response.getStatus())
        .describedAs("arranged: refused as too large, which answered: %s", contentOf(response))
        .isEqualTo(CONTENT_TOO_LARGE);
    assertThat(accounts.findBySignInName(name).orElseThrow().failedSignIns())
        .describedAs("failed sign-ins after an oversized body was refused")
        .isZero();
  }

  /**
   * The control for the test above: the same account, the same wrong password, and a body only one
   * byte shorter, at the limit. It does count, so the zero above is the limit's doing and not a
   * wrong password that would never have counted.
   */
  @Test
  void
      GivenAWrongPasswordBodyAtTheLimitNamingARealAccount_WhenSigningIn_ThenOneFailedAttemptIsCounted()
          throws Exception {
    SignInName name = anAccount();

    MockHttpServletResponse response = signIn(bodyOfLength(name.value(), maximumDocumentBytes));

    assertThat(response.getStatus())
        .describedAs(
            "arranged: refused as a wrong password, which answered: %s", contentOf(response))
        .isEqualTo(401);
    assertThat(accounts.findBySignInName(name).orElseThrow().failedSignIns())
        .describedAs("failed sign-ins after a wrong password at the limit")
        .isEqualTo(1);
  }

  /** The boundary itself: the limit is a maximum, so a body of exactly that length is accepted. */
  @Test
  void GivenABodyExactlyAtTheLimit_WhenSigningInWithAWrongPassword_ThenItIsTheUsualRefusal()
      throws Exception {
    MockHttpServletResponse response = signIn(bodyOfLength(anInventedName(), maximumDocumentBytes));

    assertThat(response.getStatus()).isEqualTo(401);
  }

  /**
   * The limit counts bytes, not characters. This body is exactly at the limit in characters, which
   * an ASCII body may be, and one byte over it in UTF-8, because one of its characters takes two.
   */
  @Test
  void
      GivenAUtf8BodyAtTheLimitInCharactersButOverItInBytes_WhenSigningIn_ThenItIsRefusedAsTooLarge()
          throws Exception {
    String name = anInventedName();
    String body = bodyOfLengthEndingInATwoByteCharacter(name, maximumDocumentBytes + 1);
    assertThat(body.length())
        .describedAs("arranged: the body's length in characters")
        .isEqualTo(maximumDocumentBytes);

    MockHttpServletResponse response = signIn(body);

    assertRefusedAsTooLarge(response, name);
  }

  @Test
  void GivenAnOrdinaryBody_WhenSigningInWithTheRightPassword_ThenItIsSignedIn() throws Exception {
    SignInName name = anAccount();

    MockHttpServletResponse response =
        signIn("{\"signInName\":\"%s\",\"password\":\"%s\"}".formatted(name.value(), PASSWORD));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void
      GivenAnAdminAndABodyOneByteOverTheLimit_WhenReplacingTheConstraintSet_ThenItIsRefusedAsTooLarge()
          throws Exception {
    MockHttpServletRequestBuilder request =
        asAnAdmin(put(CONSTRAINTS))
            .content(paddedToLength(INVENTED_CONSTRAINT_SET, maximumDocumentBytes + 1));

    MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();

    assertRefusedAsTooLarge(response, CONSTRAINT_FIELD);
  }

  /** The control for the test above: its padding is harmless, so only its length can refuse it. */
  @Test
  void GivenAnAdminAndABodyAtTheLimit_WhenReplacingTheConstraintSet_ThenItIsReplaced()
      throws Exception {
    MockHttpServletRequestBuilder request =
        asAnAdmin(put(CONSTRAINTS))
            .content(paddedToLength(INVENTED_CONSTRAINT_SET, maximumDocumentBytes));

    mockMvc.perform(request).andExpect(status().isOk());
  }

  @Test
  void GivenAnAdminAndABodyOneByteOverTheLimit_WhenAddingAnEmployee_ThenItIsRefusedAsTooLarge()
      throws Exception {
    String employee = anInventedEmployeeName();
    MockHttpServletRequestBuilder request =
        asAnAdmin(post(EMPLOYEES))
            .content(paddedToLength(anEmployeeNamed(employee), maximumDocumentBytes + 1));

    MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();

    assertRefusedAsTooLarge(response, employee);
  }

  /** The control for the test above, as for the constraint set. */
  @Test
  void GivenAnAdminAndABodyAtTheLimit_WhenAddingAnEmployee_ThenTheEmployeeIsAdded()
      throws Exception {
    MockHttpServletRequestBuilder request =
        asAnAdmin(post(EMPLOYEES))
            .content(
                paddedToLength(anEmployeeNamed(anInventedEmployeeName()), maximumDocumentBytes));

    mockMvc.perform(request).andExpect(status().isCreated());
  }

  /**
   * @param response the answer to an oversized body
   * @param sent pieces of what was sent, none of which the refusal may repeat
   */
  private static void assertRefusedAsTooLarge(MockHttpServletResponse response, String... sent)
      throws Exception {
    String body = contentOf(response);
    assertThat(response.getStatus())
        .describedAs("status of the refusal, which answered: %s", body)
        .isEqualTo(CONTENT_TOO_LARGE);
    assertThat(response.getContentType())
        .describedAs("the refusal carries a problem detail")
        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String title = JsonPath.read(body, "$.title");
    String detail = JsonPath.read(body, "$.detail");
    assertSoftly(
        softly -> {
          softly.assertThat(title).describedAs("title").isEqualTo(TOO_LARGE_TITLE);
          softly.assertThat(detail).describedAs("detail").isNotBlank();
          for (String piece : sent) {
            softly
                .assertThat(detail)
                .describedAs("detail, which must not repeat what was sent")
                .doesNotContain(piece);
          }
        });
  }

  private static String contentOf(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
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

  /**
   * A sign-in body of exactly {@code length} bytes in UTF-8, whose wrong password ends in one
   * {@link #TWO_BYTE_CHARACTER}, so it is one character shorter than it is bytes long.
   *
   * @param signInName the name to sign in as
   * @param length the length of the whole document, in bytes
   */
  private static String bodyOfLengthEndingInATwoByteCharacter(String signInName, int length) {
    String prefix = "{\"signInName\":\"%s\",\"password\":\"".formatted(signInName);
    String suffix = TWO_BYTE_CHARACTER + "\"}";
    int suffixBytes = suffix.getBytes(StandardCharsets.UTF_8).length;
    String body =
        prefix + WRONG_PASSWORD_CHARACTER.repeat(length - prefix.length() - suffixBytes) + suffix;
    assertThat(body.getBytes(StandardCharsets.UTF_8))
        .describedAs("arranged: the body's length in bytes")
        .hasSize(length);
    return body;
  }

  /**
   * A JSON object of exactly {@code length} bytes: the members given, whitespace, then the closing
   * brace. Whitespace between tokens means nothing to JSON, so the object reads exactly as it would
   * unpadded.
   *
   * @param openObject an ASCII object with its closing brace left off
   * @param length the length of the whole document, in bytes
   */
  private static String paddedToLength(String openObject, int length) {
    String closing = "}";
    String body =
        openObject + " ".repeat(length - openObject.length() - closing.length()) + closing;
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

  /** The request, as the Admin of a freshly onboarded organisation, with a JSON content type. */
  private MockHttpServletRequestBuilder asAnAdmin(MockHttpServletRequestBuilder request)
      throws Exception {
    String onboarded =
        mockMvc
            .perform(
                TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                    .content(
                        TestTenants.onboarding("Organisation %s".formatted(UUID.randomUUID()))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return TestTenants.as(mockMvc, request, JsonPath.read(onboarded, "$.id"), "ADMIN");
  }

  /** An ASCII employee body with its closing brace left off, for {@link #paddedToLength}. */
  private static String anEmployeeNamed(String name) {
    return "{\"name\":\"%s\"".formatted(name);
  }

  private static String anInventedEmployeeName() {
    return "Invented Body-Limit Employee %s".formatted(UUID.randomUUID());
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
            clock.instant()));
    return name;
  }
}
