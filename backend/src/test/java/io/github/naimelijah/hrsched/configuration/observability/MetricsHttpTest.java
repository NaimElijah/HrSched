package io.github.naimelijah.hrsched.configuration.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Specifies what is instrumented, what an anonymous caller can reach over HTTP, and what they
 * deliberately cannot.
 *
 * <p>The negative cases are the point. `management.endpoints.web.exposure.include` is an allow
 * list, so a careless edit that opens `loggers` or `env` would hand an unauthenticated caller the
 * ability to change log levels or read configuration. Naming the endpoints that must stay shut
 * turns that from a review comment into a build failure.
 *
 * <p>The metrics themselves are refused to an anonymous caller too (issue #250). The {@code
 * hrsched.changes} counter is tagged by change kind, and {@code ACCOUNT_LOCKED} is a kind only a
 * real, active account can produce, so a scrape answered to anybody would tell them which sign-in
 * names exist. That instrumentation is recorded is therefore asserted by reading the application's
 * {@link MeterRegistry}, not by scraping it over HTTP.
 *
 * <p>{@code @AutoConfigureMetrics} is not decoration. Spring Boot switches metrics exporters off
 * under test — it sets {@code management.defaults.metrics.export.enabled} false — so without it
 * {@code /actuator/prometheus} answers 404 in a test while existing perfectly well in production,
 * and the refusals below would be measuring the test harness rather than the application. The cost
 * is a second Spring context: unlike every other {@code @SpringBootTest} here, this class cannot
 * share the one the rest use.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
class MetricsHttpTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private MeterRegistry meters;

  @Test
  void GivenTheProbesAreEnabled_WhenAsked_ThenLivenessAndReadinessBothAnswer() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
  }

  @Test
  void GivenNoToken_WhenAskingForHealth_ThenItAnswers() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void GivenNoToken_WhenAskingForInfo_ThenItAnswers() throws Exception {
    mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
  }

  @Test
  void GivenTheApplicationIsRunning_WhenReadingTheRegistry_ThenTheSaturationMetersAreThere() {
    assertThat(meters.find("jvm.memory.used").gauges()).describedAs("jvm.memory.used").isNotEmpty();
    assertThat(meters.find("jvm.threads.live").gauge()).describedAs("jvm.threads.live").isNotNull();
    assertThat(meters.find("process.uptime").timeGauge()).describedAs("process.uptime").isNotNull();
  }

  /**
   * The meter that answers whether the product did its job: how many places a generation was asked
   * to fill, and how many it could not fill legally. Registered when the generator is constructed,
   * so a dashboard shows a zero rather than "no data" before the first roster.
   */
  @Test
  void GivenNoRosterHasBeenGenerated_WhenReadingTheRegistry_ThenTheCoverageMetersAreRegistered() {
    assertThat(meters.find("hrsched.shifts.required").summary())
        .describedAs("hrsched.shifts.required")
        .isNotNull();
    assertThat(meters.find("hrsched.shifts.uncovered").summary())
        .describedAs("hrsched.shifts.uncovered")
        .isNotNull();
  }

  @Test
  void GivenARequestHasBeenServed_WhenReadingTheRegistry_ThenItsLatencyAndStatusWereRecorded()
      throws Exception {
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

    mockMvc
        .perform(
            TestTenants.as(
                mockMvc, get("/api/qualifications"), JsonPath.read(body, "$.id"), "ADMIN"))
        .andExpect(status().isOk());

    assertThat(
            meters
                .find("http.server.requests")
                .tag("uri", "/api/qualifications")
                .tag("status", "200")
                .timers())
        .describedAs("http.server.requests for GET /api/qualifications answered 200")
        .isNotEmpty();
  }

  /** Every meter, in Prometheus's format, to whoever asks: the scrape issue #250 closes. */
  @Test
  void GivenNoToken_WhenScrapingPrometheus_ThenItIsUnauthorised() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void GivenNoToken_WhenListingTheMetrics_ThenItIsUnauthorised() throws Exception {
    mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
  }

  /**
   * The meter that gives an account's existence away, read by name. A change is recorded first, so
   * the meter exists and the only thing that can refuse the read is the security chain.
   */
  @Test
  void GivenAChangeHasBeenRecorded_WhenReadingTheChangesCounterWithoutAToken_ThenItIsUnauthorised()
      throws Exception {
    mockMvc
        .perform(
            TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                .content(TestTenants.onboarding("Organisation %s".formatted(UUID.randomUUID()))))
        .andExpect(status().isCreated());
    assertThat(meters.find("hrsched.changes").counters())
        .describedAs("arranged: onboarding records a change")
        .isNotEmpty();

    mockMvc.perform(get("/actuator/metrics/hrsched.changes")).andExpect(status().isUnauthorized());
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
