package io.github.naimelijah.hrsched.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

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

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @LocalServerPort private int port;

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
  void GivenNoToken_WhenAskingForTheErrorPageDirectly_ThenItIsRefused() throws Exception {
    HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/error")).GET());

    assertThat(response.statusCode())
        .describedAs("status, which answered: %s", response.body())
        .isEqualTo(UNAUTHORIZED);
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
