package io.github.naimelijah.hrsched.configuration.observability;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Specifies what an operator can reach over HTTP, and what they deliberately cannot.
 *
 * <p>The negative cases are the point. `management.endpoints.web.exposure.include` is an allow
 * list, so a careless edit that opens `loggers` or `env` would hand an unauthenticated caller the
 * ability to change log levels or read configuration. Naming the endpoints that must stay shut
 * turns that from a review comment into a build failure.
 *
 * <p>{@code @AutoConfigureMetrics} is not decoration. Spring Boot switches metrics exporters off
 * under test — it sets {@code management.defaults.metrics.export.enabled} false — so without it
 * {@code /actuator/prometheus} answers 404 in a test while existing perfectly well in production,
 * and the assertions below would be measuring the test harness rather than the application. The
 * cost is a second Spring context: unlike every other {@code @SpringBootTest} here, this class
 * cannot share the one the rest use.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
class MetricsHttpTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void GivenTheProbesAreEnabled_WhenAsked_ThenLivenessAndReadinessBothAnswer() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
  }

  @Test
  void GivenAScraper_WhenItReadsTheMetrics_ThenTheSaturationMetersAreThere() throws Exception {
    mockMvc
        .perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("jvm_memory_used_bytes")))
        .andExpect(content().string(containsString("jvm_threads_live_threads")))
        .andExpect(content().string(containsString("process_uptime_seconds")));
  }

  /**
   * The meter that answers whether the product did its job: how many places a generation was asked
   * to fill, and how many it could not fill legally. Registered when the generator is constructed,
   * so a dashboard shows a zero rather than "no data" before the first roster.
   */
  @Test
  void GivenNoRosterHasBeenGenerated_WhenScraping_ThenTheCoverageMetersAreStillPublished()
      throws Exception {
    mockMvc
        .perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("hrsched_shifts_required")))
        .andExpect(content().string(containsString("hrsched_shifts_uncovered")));
  }

  @Test
  void GivenARequestHasBeenServed_WhenScraping_ThenItsLatencyAndStatusWereRecorded()
      throws Exception {
    String body =
        mockMvc
            .perform(
                TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                    .content("{\"name\":\"Organisation %s\"}".formatted(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    mockMvc
        .perform(
            get("/api/qualifications")
                .header("X-Organisation", JsonPath.read(body, "$.id").toString())
                .header("X-User", UUID.randomUUID().toString())
                .header("X-Role", "ADMIN"))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("http_server_requests_seconds")));
  }

  @Test
  void GivenNoAuthenticationExists_WhenAskingForTheLoggersEndpoint_ThenItIsNotExposed()
      throws Exception {
    mockMvc.perform(get("/actuator/loggers")).andExpect(status().isNotFound());
  }

  @Test
  void GivenNoAuthenticationExists_WhenAskingForTheEnvironment_ThenItIsNotExposed()
      throws Exception {
    mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
  }

  @Test
  void GivenNoAuthenticationExists_WhenAskingForAHeapDump_ThenItIsNotExposed() throws Exception {
    mockMvc.perform(get("/actuator/heapdump")).andExpect(status().isNotFound());
  }
}
