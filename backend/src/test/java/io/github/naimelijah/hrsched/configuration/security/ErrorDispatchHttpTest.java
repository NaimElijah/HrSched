package io.github.naimelijah.hrsched.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Specification that the security chain lets the container render an error, and lets nobody ask for
 * the error page.
 *
 * <p>When a request fails in a way no handler answers, the container renders the error by
 * dispatching once more, to {@code /error}, and that second dispatch passes through the security
 * chain too. The chain lets every {@code ERROR} dispatch through, whoever made the request, because
 * {@code denyAll} would otherwise turn the error into a refusal and hide what went wrong. The
 * request firewall's 400 is such a failure any anonymous caller can provoke, so it pins that an
 * {@code ERROR} dispatch carrying no token is rendered rather than refused. A request a caller
 * makes to {@code /error} is a {@code REQUEST} dispatch, and no rule opens it, so it is refused
 * like any other route nobody decided was safe (ADR-032).
 *
 * <p>A wrong method on a mapped path used to reach the error page too; Spring's own refusals are
 * now answered with a problem detail in the request itself (issue #275), which the first test pins
 * on a real port, headers included.
 *
 * <p>The page the container renders carries the same {@code Content-Security-Policy} and {@code
 * nosniff} as every other answer, as JSON and as the HTML a browser asks for. The chain's header
 * writer does not run in the {@code ERROR} dispatch: the headers reach that page only because the
 * first dispatch's response wrote them when the error was sent, so nothing but a test on a real
 * port would notice them going. The firewall's 400 is written before that first dispatch's headers
 * are, so those tests use the other failure that still reaches the page: a form body over the
 * server's form limit, which the server refuses while Spring is reading the body (see {@code
 * FormPostLimitHttpTest}). That is the case issue #340 removes, by answering the oversized form
 * with a problem detail; when it does, these tests need another failure that is rendered at {@code
 * /error} after the chain has run.
 *
 * <p>The form's one field and the character its value repeats are invented. Real HTTP on a random
 * port, not MockMvc, because MockMvc never performs the container's error dispatch. Annotated as
 * {@code FormPostLimitHttpTest} is, so the two share one real-port context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ErrorDispatchHttpTest {

  private static final String HEALTH = "/actuator/health";

  private static final String EMPLOYEES = "/api/employees";

  private static final int BAD_REQUEST = 400;

  private static final int METHOD_NOT_ALLOWED = 405;

  private static final int CONTENT_TOO_LARGE = 413;

  /** Over the default 1 MiB limit, under Tomcat's default 2 MiB swallow, as in the form test. */
  private static final int FORM_BYTES = 3 * 512 * 1024;

  /** Invented: the one field the form holds. */
  private static final String INVENTED_FIELD = "name=";

  /** Invented: what its value is filled with. */
  private static final String INVENTED_CHARACTER = "x";

  private static final int UNAUTHORIZED = 401;

  private static final int FORBIDDEN = 403;

  /**
   * A path whose {@code %2561} decodes twice, which the request firewall refuses before any rule is
   * matched (see {@code SessionHttpTest}).
   */
  private static final String A_PATH_THE_FIREWALL_REFUSES = "/%2561pi/auth/session";

  private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
  private static final String NOTHING_LOADS_AND_NOTHING_FRAMES =
      "default-src 'none'; frame-ancestors 'none'";

  private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
  private static final String NOSNIFF = "nosniff";

  private static final String JSON = "application/json";
  private static final String HTML = "text/html";
  private static final String PROBLEM_JSON = "application/problem+json";

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @LocalServerPort private int port;

  /** Signs accounts in, for tokens to send over the real port. */
  @Autowired private MockMvc mockMvc;

  /**
   * Health answers {@code GET} to anybody and has no {@code POST}. Spring MVC's 405 for it used to
   * be rendered at {@code /error}; it is now a problem detail written in the request, with the
   * chain's headers, and never reaches the error page.
   */
  @Test
  void GivenNoToken_WhenPostingToARouteThatOnlyReads_ThenItIsAProblemDetailNotTheErrorPage()
      throws Exception {
    HttpResponse<String> response = send(HttpRequest.newBuilder(uri(HEALTH)).POST(noBody()));

    assertThat(response.statusCode())
        .describedAs("status, which answered: %s", response.body())
        .isEqualTo(METHOD_NOT_ALLOWED);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith(PROBLEM_JSON));
    assertThat(JsonPath.<String>read(response.body(), "$.type"))
        .isEqualTo("urn:hrsched:problem:method-not-allowed");
    assertThat(response.headers().firstValue(CONTENT_SECURITY_POLICY))
        .as("the Content-Security-Policy of the problem detail")
        .hasValue(NOTHING_LOADS_AND_NOTHING_FRAMES);
    assertThat(response.headers().firstValue(CONTENT_TYPE_OPTIONS))
        .as("the X-Content-Type-Options of the problem detail")
        .hasValue(NOSNIFF);
  }

  /**
   * The firewall refuses the path by sending an error, which the container renders at {@code
   * /error} in a dispatch that carries no token, because the request carried none. Refused there,
   * it would answer 401 and say nothing of the path that failed.
   */
  @Test
  void GivenNoTokenAndAPathTheFirewallRefuses_WhenSent_ThenTheErrorIsRenderedRatherThanRefused()
      throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(uri(A_PATH_THE_FIREWALL_REFUSES)).GET());

    assertThat(response.statusCode())
        .describedAs("status, which answered: %s", response.body())
        .isNotIn(UNAUTHORIZED, FORBIDDEN)
        .isEqualTo(BAD_REQUEST);
    assertThat(JsonPath.<Integer>read(response.body(), "$.status"))
        .describedAs("the status the rendered error names")
        .isEqualTo(BAD_REQUEST);
  }

  /**
   * The server refuses a form over its limit while Spring reads the body, and the container renders
   * that at {@code /error}. The error page names the path that failed, which a refusal written by
   * the chain or by a handler would not.
   */
  @Test
  void GivenAFormOverTheServersLimit_WhenPosted_ThenTheContainersErrorIsRendered()
      throws Exception {
    HttpResponse<String> response = renderedError(JSON);

    assertThat(JsonPath.<String>read(response.body(), "$.path"))
        .describedAs("the path the rendered error names")
        .isEqualTo(EMPLOYEES);
  }

  @Test
  void GivenAFormOverTheServersLimit_WhenPosted_ThenTheRenderedErrorCarriesTheCsp()
      throws Exception {
    HttpResponse<String> response = renderedError(JSON);

    assertThat(response.headers().firstValue(CONTENT_SECURITY_POLICY))
        .as("the Content-Security-Policy of the rendered error")
        .hasValue(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenAFormOverTheServersLimit_WhenPosted_ThenTheRenderedErrorForbidsSniffing()
      throws Exception {
    HttpResponse<String> response = renderedError(JSON);

    assertThat(response.headers().firstValue(CONTENT_TYPE_OPTIONS))
        .as("the X-Content-Type-Options of the rendered error")
        .hasValue(NOSNIFF);
  }

  /** The page a browser is given, which is the one a policy exists to make inert. */
  @Test
  void GivenABrowserAskingForHtml_WhenPostingAFormOverTheServersLimit_ThenThePageCarriesTheCsp()
      throws Exception {
    HttpResponse<String> response = renderedError(HTML);

    assertThat(response.headers().firstValue(CONTENT_SECURITY_POLICY))
        .as("the Content-Security-Policy of the rendered HTML error")
        .hasValue(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenABrowserAskingForHtml_WhenPostingAFormOverTheServersLimit_ThenThePageForbidsSniffing()
      throws Exception {
    HttpResponse<String> response = renderedError(HTML);

    assertThat(response.headers().firstValue(CONTENT_TYPE_OPTIONS))
        .as("the X-Content-Type-Options of the rendered HTML error")
        .hasValue(NOSNIFF);
  }

  @Test
  void GivenNoToken_WhenAskingForTheErrorPageDirectly_ThenItIsRefused() throws Exception {
    HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/error")).GET());

    assertThat(response.statusCode())
        .describedAs("status, which answered: %s", response.body())
        .isEqualTo(UNAUTHORIZED);
  }

  /**
   * Refused to a signed-in caller too, with a 403 rather than a 401: the page is closed to
   * everybody, not merely behind a sign-in.
   */
  @Test
  void GivenTheOperatorsToken_WhenAskingForTheErrorPageDirectly_ThenItIsForbidden()
      throws Exception {
    String token = TestTenants.operatorToken(mockMvc);

    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(uri("/error")).header("Authorization", "Bearer " + token).GET());

    assertThat(response.statusCode())
        .describedAs("status, which answered: %s", response.body())
        .isEqualTo(FORBIDDEN);
  }

  /** The container's rendering of an oversized form's 413, asked for in the given media type. */
  private HttpResponse<String> renderedError(String accept) throws Exception {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(uri(EMPLOYEES))
                .header("Authorization", "Bearer " + anAdminsToken())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(aFormOverTheLimit())));
    assertThat(response.statusCode())
        .describedAs("arranged: rendered, which answered: %s", response.body())
        .isEqualTo(CONTENT_TOO_LARGE);
    assertThat(response.headers().firstValue("Content-Type"))
        .describedAs("arranged: rendered as %s", accept)
        .hasValueSatisfying(type -> assertThat(type).startsWith(accept));
    return response;
  }

  /** The token of an Admin of a freshly onboarded organisation, for a route that takes a body. */
  private String anAdminsToken() throws Exception {
    String onboarded =
        mockMvc
            .perform(
                TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                    .content(
                        TestTenants.onboarding("Organisation %s".formatted(UUID.randomUUID()))))
            .andReturn()
            .getResponse()
            .getContentAsString();
    OrganisationId organisation =
        new OrganisationId(UUID.fromString(JsonPath.read(onboarded, "$.id")));
    return TestTenants.tokenFor(mockMvc, organisation, Role.ADMIN);
  }

  /** One field whose value fills the body to {@link #FORM_BYTES} bytes. */
  private static byte[] aFormOverTheLimit() {
    return (INVENTED_FIELD + INVENTED_CHARACTER.repeat(FORM_BYTES - INVENTED_FIELD.length()))
        .getBytes(StandardCharsets.US_ASCII);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:%d%s".formatted(port, path));
  }

  private static HttpRequest.BodyPublisher noBody() {
    return HttpRequest.BodyPublishers.noBody();
  }

  private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build()) {
      return client.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
