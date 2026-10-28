package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Specification that a form body posted to a route that takes JSON is held to the same limit as a
 * JSON body (issue #250).
 *
 * <p>A route that names no {@code consumes} asks for its body before it knows the body is not JSON,
 * and for a form POST that makes the server parse the form, up to the server's own form limit, and
 * then refuse it as the wrong type. That limit is the JSON one, so a form body over it is refused
 * for its size, which only the server's form parser can know, instead of being parsed and then
 * refused for its type.
 *
 * <p>Real HTTP on a random port, not MockMvc, because MockMvc parses form content itself and never
 * reaches the server's parser. The Admin's token comes from MockMvc against the same context. That
 * costs a context of its own.
 *
 * <p>The body is 1.5 MiB of an invented field: over the 1 MiB default limit, and under the 2 MiB
 * the server reads and discards from a body it answered without reading, so the answer is not a
 * reset connection.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class FormPostLimitHttpTest {

  /** Over the default 1 MiB limit, under Tomcat's default 2 MiB swallow. */
  private static final int BODY_BYTES = 3 * 512 * 1024;

  /** Invented: the one field the form holds. */
  private static final String FIELD = "name=";

  /** Invented: what its value is filled with. */
  private static final String INVENTED_CHARACTER = "x";

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @LocalServerPort private int port;

  @Autowired private MockMvc mockMvc;

  @Test
  void
      GivenAnAdminAndAFormBodyOverTheLimit_WhenPostedToARouteThatTakesJson_ThenItIsRefusedAsTooLarge()
          throws Exception {
    String token = anAdminsToken();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:%d/api/employees".formatted(port)))
            .timeout(TIMEOUT)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .POST(HttpRequest.BodyPublishers.ofByteArray(aFormBody()))
            .build();

    HttpResponse<String> response;
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build()) {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    assertThat(response.statusCode())
        .describedAs("status, which answered: %s", response.body())
        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
  }

  /** The token of an Admin of a freshly onboarded organisation. */
  private String anAdminsToken() throws Exception {
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
    OrganisationId organisation =
        new OrganisationId(UUID.fromString(JsonPath.read(onboarded, "$.id")));
    return TestTenants.tokenFor(mockMvc, organisation, Role.ADMIN);
  }

  /** One field whose value fills the body to {@link #BODY_BYTES} bytes. */
  private static byte[] aFormBody() {
    String body = FIELD + INVENTED_CHARACTER.repeat(BODY_BYTES - FIELD.length());
    return body.getBytes(StandardCharsets.US_ASCII);
  }
}
