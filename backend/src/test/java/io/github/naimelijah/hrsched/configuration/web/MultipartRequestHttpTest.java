package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

/**
 * Specification that a multipart request is never parsed (issue #250).
 *
 * <p>No route of this API takes a multipart body. Yet Spring's dispatcher parses one, writing its
 * parts to disk, before it has chosen a handler, so an anonymous caller could make the server read
 * and store parts on a route that would then refuse them. Sign-in is the route, because it is open
 * to everybody. A multipart request to it is answered 415, the answer to any body sign-in does not
 * take, and is not parsed first. A part over the default upload limit tells the two apart: parsed,
 * it is refused 413 for its size, which only a parser can know; unparsed, it is refused 415 for its
 * type, with the problem detail any type a route does not take gets (issue #275).
 *
 * <p>Real HTTP on a random port, not MockMvc, because MockMvc never parses a multipart body: its
 * mock request hands the dispatcher only the parts a test registered on it, so the parser this is
 * about never runs. It sends nothing through MockMvc, and carries {@code @AutoConfigureMockMvc}
 * only so that its annotations match {@code FormPostLimitHttpTest}'s and the two share one
 * real-port context.
 *
 * <p>The part is 1.5 MiB of an invented byte: over the default 1 MB limit on a part, and under the
 * 2 MiB Tomcat reads and discards from a body it answered without reading. Larger, and a server
 * that answers without reading would close the connection while the client was still sending, which
 * would fail this for a reason that is not the behaviour.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class MultipartRequestHttpTest {

  private static final String SIGN_IN = "/api/auth/sign-in";

  private static final String BOUNDARY = "invented-multipart-boundary";

  /** Over the default 1 MB part limit, under Tomcat's default 2 MiB swallow. */
  private static final int PART_BYTES = 3 * 512 * 1024;

  /** Invented: what the part is filled with. */
  private static final byte INVENTED_PART_BYTE = 'x';

  private static final int UNSUPPORTED_MEDIA_TYPE = 415;

  private static final String UNSUPPORTED_MEDIA_TYPE_PROBLEM =
      "urn:hrsched:problem:unsupported-media-type";

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @LocalServerPort private int port;

  @Test
  void GivenAnAnonymousMultipartRequestWithALargePart_WhenSigningIn_ThenItIsRefusedUnparsed()
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, SIGN_IN)))
            .timeout(TIMEOUT)
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + BOUNDARY)
            .POST(HttpRequest.BodyPublishers.ofByteArray(aMultipartBody()))
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
        .isEqualTo(UNSUPPORTED_MEDIA_TYPE);
    assertThat(JsonPath.<String>read(response.body(), "$.type"))
        .describedAs("the refusal's type, which answered: %s", response.body())
        .isEqualTo(UNSUPPORTED_MEDIA_TYPE_PROBLEM);
  }

  /** One file part of {@link #PART_BYTES} bytes, framed as RFC 7578 says. */
  private static byte[] aMultipartBody() {
    byte[] part = new byte[PART_BYTES];
    Arrays.fill(part, INVENTED_PART_BYTE);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.writeBytes(
        ("--%s\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"invented.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .formatted(BOUNDARY)
            .getBytes(StandardCharsets.US_ASCII));
    body.writeBytes(part);
    body.writeBytes("\r\n--%s--\r\n".formatted(BOUNDARY).getBytes(StandardCharsets.US_ASCII));
    return body.toByteArray();
  }
}
