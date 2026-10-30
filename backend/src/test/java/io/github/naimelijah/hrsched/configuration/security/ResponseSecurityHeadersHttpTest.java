package io.github.naimelijah.hrsched.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Specification that every backend response tells a browser to do nothing with it but read it
 * (issue #250).
 *
 * <p>The API answers with JSON and nothing else, so no response of its has any reason to load a
 * script, a style or an image, to be framed, or to send the URL it came from onward. A policy that
 * forbids all of it costs nothing and turns a response a browser is somehow tricked into rendering
 * into an inert page. It holds on every response, whoever asked and however it was answered: an
 * open route, each of the security chain's two refusals (the 401 and the 403 its handlers write), a
 * problem detail a controller's exception handler writes, the 415 {@code JsonCharsetFilter} writes
 * after the chain, and an answer to a signed-in caller.
 *
 * <p>Spring Security's own three defaults are kept beside that policy, and pinned here: a browser
 * may not guess a response's type from its content, may not frame it, and neither it nor a shared
 * cache may store it. The last is pinned on a tenant read whose controller sets no cache header of
 * its own, because sign-in and the session set {@code no-store} themselves and would hide the
 * default going.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class ResponseSecurityHeadersHttpTest {

  private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
  private static final String NOTHING_LOADS_AND_NOTHING_FRAMES =
      "default-src 'none'; frame-ancestors 'none'";

  private static final String REFERRER_POLICY = "Referrer-Policy";
  private static final String NO_REFERRER = "no-referrer";

  private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
  private static final String NOSNIFF = "nosniff";

  private static final String FRAME_OPTIONS = "X-Frame-Options";
  private static final String DENY = "DENY";

  private static final String NO_STORE = "no-store";

  private static final String SESSION = "/api/auth/session";

  /** Account administration: an Admin's, so the operator's token is refused there. */
  private static final String ACCOUNTS = "/api/accounts";

  private static final String SIGN_IN = "/api/auth/sign-in";

  private static final String ORGANISATIONS = "/api/organisations";

  private static final String QUALIFICATIONS = "/api/qualifications";

  /** JSON cut off mid-document, which the access context's handler answers 400. */
  private static final String MALFORMED_SIGN_IN = "{\"signInName\":";

  /** A charset JSON may not be sent in, which {@code JsonCharsetFilter} answers 415. */
  private static final String LATIN_1_JSON = "application/json;charset=ISO-8859-1";

  /** Invented: a sign-in body naming nobody, refused before anything reads it. */
  private static final String INVENTED_SIGN_IN = "{\"signInName\":\"x\",\"password\":\"y\"}";

  @Autowired private MockMvc mockMvc;

  @Test
  void GivenNoToken_WhenAskingForHealth_ThenTheAnswerCarriesTheContentSecurityPolicy()
      throws Exception {
    MockHttpServletResponse response = health();

    assertThat(response.getHeader(CONTENT_SECURITY_POLICY))
        .isEqualTo(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenNoToken_WhenAskingForHealth_ThenTheAnswerCarriesTheReferrerPolicy() throws Exception {
    MockHttpServletResponse response = health();

    assertThat(response.getHeader(REFERRER_POLICY)).isEqualTo(NO_REFERRER);
  }

  @Test
  void GivenNoToken_WhenReadingTheSession_ThenTheRefusalCarriesTheContentSecurityPolicy()
      throws Exception {
    MockHttpServletResponse response = sessionWithoutAToken();

    assertThat(response.getHeader(CONTENT_SECURITY_POLICY))
        .isEqualTo(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenNoToken_WhenReadingTheSession_ThenTheRefusalCarriesTheReferrerPolicy()
      throws Exception {
    MockHttpServletResponse response = sessionWithoutAToken();

    assertThat(response.getHeader(REFERRER_POLICY)).isEqualTo(NO_REFERRER);
  }

  @Test
  void GivenASignedInCaller_WhenReadingTheirSession_ThenTheAnswerCarriesTheContentSecurityPolicy()
      throws Exception {
    MockHttpServletResponse response = sessionAsTheOperator();

    assertThat(response.getHeader(CONTENT_SECURITY_POLICY))
        .isEqualTo(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenASignedInCaller_WhenReadingTheirSession_ThenTheAnswerCarriesTheReferrerPolicy()
      throws Exception {
    MockHttpServletResponse response = sessionAsTheOperator();

    assertThat(response.getHeader(REFERRER_POLICY)).isEqualTo(NO_REFERRER);
  }

  @Test
  void
      GivenTheOperatorsToken_WhenAdministeringAccounts_ThenTheRefusalCarriesTheContentSecurityPolicy()
          throws Exception {
    MockHttpServletResponse response = accountsAsTheOperator();

    assertThat(response.getHeader(CONTENT_SECURITY_POLICY))
        .isEqualTo(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenTheOperatorsToken_WhenAdministeringAccounts_ThenTheRefusalCarriesTheReferrerPolicy()
      throws Exception {
    MockHttpServletResponse response = accountsAsTheOperator();

    assertThat(response.getHeader(REFERRER_POLICY)).isEqualTo(NO_REFERRER);
  }

  @Test
  void GivenAMalformedBody_WhenSigningIn_ThenTheProblemDetailCarriesTheContentSecurityPolicy()
      throws Exception {
    MockHttpServletResponse response = malformedSignIn();

    assertThat(response.getHeader(CONTENT_SECURITY_POLICY))
        .isEqualTo(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenAMalformedBody_WhenSigningIn_ThenTheProblemDetailCarriesTheReferrerPolicy()
      throws Exception {
    MockHttpServletResponse response = malformedSignIn();

    assertThat(response.getHeader(REFERRER_POLICY)).isEqualTo(NO_REFERRER);
  }

  @Test
  void GivenNoToken_WhenAskingForHealth_ThenTheAnswerForbidsSniffingItsType() throws Exception {
    MockHttpServletResponse response = health();

    assertThat(response.getHeader(CONTENT_TYPE_OPTIONS)).isEqualTo(NOSNIFF);
  }

  @Test
  void GivenNoToken_WhenAskingForHealth_ThenTheAnswerForbidsBeingFramed() throws Exception {
    MockHttpServletResponse response = health();

    assertThat(response.getHeader(FRAME_OPTIONS)).isEqualTo(DENY);
  }

  @Test
  void GivenAnAdmin_WhenReadingQualifications_ThenTheAnswerMayNotBeStored() throws Exception {
    MockHttpServletResponse response = qualificationsAsAnAdmin();

    assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
        .as("the Cache-Control of an answer its controller set none on")
        .contains(NO_STORE);
  }

  @Test
  void GivenJsonDeclaredInLatin1_WhenSigningIn_ThenTheRefusalCarriesTheContentSecurityPolicy()
      throws Exception {
    MockHttpServletResponse response = latin1SignIn();

    assertThat(response.getHeader(CONTENT_SECURITY_POLICY))
        .isEqualTo(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenJsonDeclaredInLatin1_WhenSigningIn_ThenTheRefusalCarriesTheReferrerPolicy()
      throws Exception {
    MockHttpServletResponse response = latin1SignIn();

    assertThat(response.getHeader(REFERRER_POLICY)).isEqualTo(NO_REFERRER);
  }

  private MockHttpServletResponse health() throws Exception {
    MockHttpServletResponse response =
        mockMvc.perform(get("/actuator/health")).andReturn().getResponse();
    assertThat(response.getStatus()).describedAs("arranged: health answers").isEqualTo(200);
    return response;
  }

  private MockHttpServletResponse sessionWithoutAToken() throws Exception {
    MockHttpServletResponse response = mockMvc.perform(get(SESSION)).andReturn().getResponse();
    assertThat(response.getStatus()).describedAs("arranged: refused").isEqualTo(401);
    return response;
  }

  /** Refused by {@code ProblemAccessDeniedHandler}, the chain's 403. */
  private MockHttpServletResponse accountsAsTheOperator() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(TestTenants.with(get(ACCOUNTS), TestTenants.operatorToken(mockMvc)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).describedAs("arranged: refused").isEqualTo(403);
    assertThat(response.getContentType())
        .describedAs("arranged: refused with a problem detail")
        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    return response;
  }

  /** Answered by the access context's exception handler, past the whole chain. */
  private MockHttpServletResponse malformedSignIn() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                post(SIGN_IN).contentType(MediaType.APPLICATION_JSON).content(MALFORMED_SIGN_IN))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).describedAs("arranged: refused").isEqualTo(400);
    assertThat(response.getContentType())
        .describedAs("arranged: refused with a problem detail")
        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    return response;
  }

  /** Refused by {@code JsonCharsetFilter}, which runs after the whole chain. */
  private MockHttpServletResponse latin1SignIn() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                post(SIGN_IN)
                    .header(HttpHeaders.CONTENT_TYPE, LATIN_1_JSON)
                    .content(INVENTED_SIGN_IN.getBytes(StandardCharsets.ISO_8859_1)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).describedAs("arranged: refused").isEqualTo(415);
    assertThat(response.getContentType())
        .describedAs("arranged: refused with a problem detail")
        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    return response;
  }

  /**
   * Answered by {@code QualificationController}, which writes no cache header, in an organisation
   * onboarded for this call alone.
   */
  private MockHttpServletResponse qualificationsAsAnAdmin() throws Exception {
    String onboarded =
        mockMvc
            .perform(
                TestTenants.with(post(ORGANISATIONS), TestTenants.operatorToken(mockMvc))
                    .content(
                        TestTenants.onboarding("Organisation %s".formatted(UUID.randomUUID()))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String organisation = JsonPath.read(onboarded, "$.id");
    MockHttpServletResponse response =
        mockMvc
            .perform(TestTenants.as(mockMvc, get(QUALIFICATIONS), organisation, "ADMIN"))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).describedAs("arranged: answered").isEqualTo(200);
    return response;
  }

  private MockHttpServletResponse sessionAsTheOperator() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(TestTenants.with(get(SESSION), TestTenants.operatorToken(mockMvc)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).describedAs("arranged: answered").isEqualTo(200);
    return response;
  }
}
