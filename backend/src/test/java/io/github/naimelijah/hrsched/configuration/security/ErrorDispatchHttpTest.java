package io.github.naimelijah.hrsched.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 * <p>When Spring MVC answers a request with an error status, the container renders it by
 * dispatching once more, to {@code /error}, and that second dispatch passes through the security
 * chain too. The chain lets every {@code ERROR} dispatch through, because {@code denyAll} would
 * otherwise turn the error into a refusal and hide what went wrong. A request a caller makes to
 * {@code /error} is a {@code REQUEST} dispatch, and no rule opens it, so it is refused like any
 * other route nobody decided was safe (ADR-032).
 *
 * <p>The page the container renders carries the same {@code Content-Security-Policy} and {@code
 * nosniff} as every other answer, as JSON and as the HTML a browser asks for. The chain's header
 * writer does not run in the {@code ERROR} dispatch: the headers reach that page only because the
 * first dispatch's response wrote them when the error was sent, so nothing but a test on a real
 * port would notice them going.
 *
 * <p>Real HTTP on a random port, not MockMvc, because MockMvc never performs the container's error
 * dispatch. Annotated as {@code FormPostLimitHttpTest} is, so the two share one real-port context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ErrorDispatchHttpTest {

  private static final String HEALTH = "/actuator/health";

  private static final int METHOD_NOT_ALLOWED = 405;

  private static final int UNAUTHORIZED = 401;

  private static final int FORBIDDEN = 403;

  private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
  private static final String NOTHING_LOADS_AND_NOTHING_FRAMES =
      "default-src 'none'; frame-ancestors 'none'";

  private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
  private static final String NOSNIFF = "nosniff";

  private static final String JSON = "application/json";
  private static final String HTML = "text/html";

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @LocalServerPort private int port;

  /** Signs the operator in, for a token to send over the real port. */
  @Autowired private MockMvc mockMvc;

  /**
   * Health answers {@code GET} to anybody and has no {@code POST}, so Spring MVC answers a POST
   * with an error status, and the container renders it at {@code /error}. The error page names the
   * path that failed, which a refusal written by the chain would not.
   */
  @Test
  void GivenNoToken_WhenPostingToARouteThatOnlyReads_ThenTheContainersErrorIsRendered()
      throws Exception {
    HttpResponse<String> response = send(HttpRequest.newBuilder(uri(HEALTH)).POST(noBody()));

    assertThat(response.statusCode())
        .describedAs("status, which answered: %s", response.body())
        .isEqualTo(METHOD_NOT_ALLOWED);
    assertThat(JsonPath.<String>read(response.body(), "$.path"))
        .describedAs("the path the rendered error names")
        .isEqualTo(HEALTH);
  }

  @Test
  void GivenNoToken_WhenPostingToARouteThatOnlyReads_ThenTheRenderedErrorCarriesTheCsp()
      throws Exception {
    HttpResponse<String> response = renderedError(JSON);

    assertThat(response.headers().firstValue(CONTENT_SECURITY_POLICY))
        .as("the Content-Security-Policy of the rendered error")
        .hasValue(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenNoToken_WhenPostingToARouteThatOnlyReads_ThenTheRenderedErrorForbidsSniffing()
      throws Exception {
    HttpResponse<String> response = renderedError(JSON);

    assertThat(response.headers().firstValue(CONTENT_TYPE_OPTIONS))
        .as("the X-Content-Type-Options of the rendered error")
        .hasValue(NOSNIFF);
  }

  /** The page a browser is given, which is the one a policy exists to make inert. */
  @Test
  void GivenABrowserAskingForHtml_WhenPostingToARouteThatOnlyReads_ThenThePageCarriesTheCsp()
      throws Exception {
    HttpResponse<String> response = renderedError(HTML);

    assertThat(response.headers().firstValue(CONTENT_SECURITY_POLICY))
        .as("the Content-Security-Policy of the rendered HTML error")
        .hasValue(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenABrowserAskingForHtml_WhenPostingToARouteThatOnlyReads_ThenThePageForbidsSniffing()
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

  /** The container's rendering of a 405, asked for in the given media type. */
  private HttpResponse<String> renderedError(String accept) throws Exception {
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(uri(HEALTH)).header("Accept", accept).POST(noBody()));
    assertThat(response.statusCode())
        .describedAs("arranged: rendered, which answered: %s", response.body())
        .isEqualTo(METHOD_NOT_ALLOWED);
    assertThat(response.headers().firstValue("Content-Type"))
        .describedAs("arranged: rendered as %s", accept)
        .hasValueSatisfying(type -> assertThat(type).startsWith(accept));
    return response;
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
