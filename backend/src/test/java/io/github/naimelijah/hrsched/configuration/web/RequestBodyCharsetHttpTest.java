package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Specification that a JSON body is read as UTF-8 or not at all (issue #250).
 *
 * <p>RFC 8259 section 8.1 requires JSON exchanged between systems to be UTF-8. The body limit is a
 * number of bytes, and Jackson counts bytes only when it reads them itself, which Spring lets it do
 * for a Unicode charset. A body declared in any other charset is decoded first, and Jackson counts
 * the decoded characters. Some charsets decode many bytes into very few characters: ISO-2022-JP's
 * escape sequences, three bytes each, switch the character set and decode to nothing at all. So a
 * body over the limit in bytes passes it when declared in one of those, on sign-in, which takes no
 * token. Such a body is refused as an unsupported media type, a 415 with a problem detail, before
 * it is read and before any use case runs. JSON declared as UTF-8, or with no charset, is read as
 * before.
 *
 * <p>Sign-in is the route because it is open to everybody, and because a wrong password there is
 * persisted as a failed attempt: an account whose count is still zero is the proof that no use case
 * ran. Sign-in takes {@code application/json} alone, so a JSON type with a {@code +json} suffix is
 * sent to defining a qualification instead, a route that names no type and whose converter reads
 * every such type. The refusal comes after the security chain, so a caller the chain refuses is
 * told that first.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 *
 * <p>Every sign-in name and password here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class RequestBodyCharsetHttpTest {

  /** Invented, and long enough for the password policy. */
  private static final String PASSWORD = "an invented password for a charset test";

  /** Invented, and never the password of any account. */
  private static final String WRONG_PASSWORD = "an invented wrong password, never anybody's";

  private static final String SIGN_IN = "/api/auth/sign-in";

  private static final String LATIN_1_JSON = "application/json;charset=ISO-8859-1";

  private static final String ISO_2022_JP_JSON = "application/json;charset=ISO-2022-JP";

  private static final String UTF_8_JSON = "application/json;charset=UTF-8";

  /**
   * Unicode, which Spring's converter would read itself, and which RFC 8259 still does not allow.
   */
  private static final String UTF_16_JSON = "application/json;charset=UTF-16";

  /** A JSON type by its suffix rather than its name, which the converter reads like JSON. */
  private static final String LATIN_1_MERGE_PATCH_JSON =
      "application/merge-patch+json;charset=ISO-8859-1";

  private static final String QUALIFICATIONS = "/api/qualifications";

  /** Invented: a qualification to define. */
  private static final String INVENTED_QUALIFICATION = "{\"name\":\"Forklift\"}";

  /** ISO-2022-JP's switch to ASCII: three bytes that decode to no character at all. */
  private static final byte[] SWITCH_TO_ASCII = {0x1B, '(', 'B'};

  private static final int UNSUPPORTED_MEDIA_TYPE = 415;

  private static final int UNAUTHORIZED = 401;

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountRepository accounts;
  @Autowired private PasswordHasher passwords;
  @Autowired private Clock clock;

  @Value("${hrsched.web.max-json-document-bytes}")
  private int maximumDocumentBytes;

  @Test
  void GivenABodyDeclaredInLatin1_WhenSigningIn_ThenItIsAnUnsupportedMediaType() throws Exception {
    MockHttpServletResponse response =
        signIn(LATIN_1_JSON, signInBody(anAccount(), WRONG_PASSWORD, StandardCharsets.ISO_8859_1));

    assertUnsupportedMediaType(response);
  }

  @Test
  void GivenABodyDeclaredInLatin1NamingARealAccount_WhenSigningIn_ThenNoFailedAttemptIsCounted()
      throws Exception {
    SignInName name = anAccount();

    signIn(LATIN_1_JSON, signInBody(name, WRONG_PASSWORD, StandardCharsets.ISO_8859_1));

    assertThat(accounts.findBySignInName(name).orElseThrow().failedSignIns())
        .describedAs("failed sign-ins after a body declared in ISO-8859-1")
        .isZero();
  }

  /**
   * Over the limit in bytes, a few dozen characters once decoded: the escape sequences padding its
   * password decode to nothing. Counted in characters, it passes the limit.
   */
  @Test
  void GivenABodyOverTheLimitDeclaredInIso2022Jp_WhenSigningIn_ThenItIsAnUnsupportedMediaType()
      throws Exception {
    byte[] body = anIso2022JpBodyOverTheLimit(anAccount());

    MockHttpServletResponse response = signIn(ISO_2022_JP_JSON, body);

    assertUnsupportedMediaType(response);
  }

  @Test
  void GivenABodyDeclaredInUtf16_WhenSigningIn_ThenItIsAnUnsupportedMediaType() throws Exception {
    MockHttpServletResponse response =
        signIn(UTF_16_JSON, signInBody(anAccount(), WRONG_PASSWORD, StandardCharsets.UTF_16));

    assertUnsupportedMediaType(response);
  }

  @Test
  void GivenAnAdminAndAJsonSuffixTypeDeclaredInLatin1_WhenDefiningAQualification_ThenItIsRefused()
      throws Exception {
    String organisation = anOrganisation();

    MockHttpServletResponse response =
        mockMvc
            .perform(
                TestTenants.as(mockMvc, post(QUALIFICATIONS), organisation, "ADMIN")
                    .contentType(MediaType.parseMediaType(LATIN_1_MERGE_PATCH_JSON))
                    .content(INVENTED_QUALIFICATION.getBytes(StandardCharsets.ISO_8859_1)))
            .andReturn()
            .getResponse();

    assertUnsupportedMediaType(response);
  }

  @Test
  void GivenNoTokenAndABodyDeclaredInLatin1_WhenDefiningAQualification_ThenItIsUnauthorised()
      throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                post(QUALIFICATIONS)
                    .header(HttpHeaders.CONTENT_TYPE, LATIN_1_JSON)
                    .content(INVENTED_QUALIFICATION.getBytes(StandardCharsets.ISO_8859_1)))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus())
        .describedAs("status, which answered: %s", response.getContentAsString())
        .isEqualTo(UNAUTHORIZED);
  }

  /** The control: JSON with no charset is UTF-8 by definition, and is read. */
  @Test
  void GivenABodyDeclaredAsJsonWithNoCharset_WhenSigningInWithTheRightPassword_ThenItIsSignedIn()
      throws Exception {
    MockHttpServletResponse response =
        signIn(
            MediaType.APPLICATION_JSON_VALUE,
            signInBody(anAccount(), PASSWORD, StandardCharsets.UTF_8));

    assertThat(response.getStatus())
        .describedAs("answered: %s", response.getContentAsString())
        .isEqualTo(200);
  }

  /** The control: JSON declared as UTF-8 says what it would be anyway, and is read. */
  @Test
  void GivenABodyDeclaredAsUtf8_WhenSigningInWithTheRightPassword_ThenItIsSignedIn()
      throws Exception {
    MockHttpServletResponse response =
        signIn(UTF_8_JSON, signInBody(anAccount(), PASSWORD, StandardCharsets.UTF_8));

    assertThat(response.getStatus())
        .describedAs("answered: %s", response.getContentAsString())
        .isEqualTo(200);
  }

  private static void assertUnsupportedMediaType(MockHttpServletResponse response)
      throws Exception {
    assertThat(response.getStatus())
        .describedAs("status, which answered: %s", response.getContentAsString())
        .isEqualTo(UNSUPPORTED_MEDIA_TYPE);
    assertThat(response.getContentType())
        .describedAs("the refusal carries a problem detail")
        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
  }

  private static byte[] signInBody(SignInName name, String password, Charset charset) {
    return "{\"signInName\":\"%s\",\"password\":\"%s\"}"
        .formatted(name.value(), password)
        .getBytes(charset);
  }

  /**
   * A wrong-password sign-in body, longer in bytes than the limit, padded inside its password with
   * ISO-2022-JP's switch to ASCII, repeated. The body never leaves ASCII, so each switch changes
   * nothing and decodes to nothing.
   */
  private byte[] anIso2022JpBodyOverTheLimit(SignInName name) {
    byte[] prefix =
        "{\"signInName\":\"%s\",\"password\":\"x"
            .formatted(name.value())
            .getBytes(StandardCharsets.US_ASCII);
    byte[] suffix = "\"}".getBytes(StandardCharsets.US_ASCII);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.writeBytes(prefix);
    while (body.size() + suffix.length <= maximumDocumentBytes) {
      body.writeBytes(SWITCH_TO_ASCII);
    }
    body.writeBytes(suffix);
    byte[] bytes = body.toByteArray();
    assertThat(bytes.length)
        .describedAs("arranged: the body's length in bytes")
        .isGreaterThan(maximumDocumentBytes);
    assertThat(new String(bytes, Charset.forName("ISO-2022-JP")).length())
        .describedAs("arranged: the body's length in characters, once decoded")
        .isLessThan(prefix.length + suffix.length + 1);
    return bytes;
  }

  private MockHttpServletResponse signIn(String contentType, byte[] body) throws Exception {
    return mockMvc
        .perform(post(SIGN_IN).header(HttpHeaders.CONTENT_TYPE, contentType).content(body))
        .andReturn()
        .getResponse();
  }

  private String anOrganisation() throws Exception {
    String body =
        mockMvc
            .perform(
                TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                    .content(
                        TestTenants.onboarding("Organisation %s".formatted(UUID.randomUUID()))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  /** A fresh operator account nothing else signs in as, so its failure count starts at zero. */
  private SignInName anAccount() {
    SignInName name = new SignInName("charset-%s@example.com".formatted(UUID.randomUUID()));
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
