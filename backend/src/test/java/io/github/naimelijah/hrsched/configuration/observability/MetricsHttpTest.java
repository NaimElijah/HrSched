package io.github.naimelijah.hrsched.configuration.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
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
 * <p>The metrics themselves are refused to every caller, with or without a token (issue #250). The
 * {@code hrsched.changes} counter is tagged by change kind, and {@code ACCOUNT_LOCKED} is a kind
 * only a real, active account can produce, so a scrape answered to anybody would tell them which
 * sign-in names exist. A request with no token is answered 401 by {@code denyAll}, {@code
 * authenticated()} and {@code hasRole(...)} alike, so it is the signed-in callers, a tenant member
 * and the operator, each refused with a 403, that tell {@code denyAll} from a narrower rule. That
 * instrumentation is recorded is therefore asserted by reading the application's registries, not by
 * scraping them over HTTP: the {@link MeterRegistry} for what is recorded, and the {@link
 * PrometheusMeterRegistry}'s own scrape for what a scraper would be given once #78 lets one in.
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

  /** The metrics endpoint's id, which the actuator index names while the endpoint is exposed. */
  private static final String METRICS_ENDPOINT = "metrics";

  /** The Prometheus endpoint's id, as above. */
  private static final String PROMETHEUS_ENDPOINT = "prometheus";

  @Autowired private MockMvc mockMvc;
  @Autowired private MeterRegistry meters;
  @Autowired private PrometheusMeterRegistry prometheus;

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
   * Another spelling of the same endpoint. A refusal that names paths rather than the endpoint lets
   * this one through to the open actuator rule, and then only the handler decides what it answers.
   */
  @Test
  void GivenNoToken_WhenScrapingPrometheusWithATrailingSlash_ThenItIsUnauthorised()
      throws Exception {
    mockMvc.perform(get("/actuator/prometheus/")).andExpect(status().isUnauthorized());
  }

  /** Another spelling of the same endpoint, as above. */
  @Test
  void GivenNoToken_WhenListingTheMetricsWithATrailingSlash_ThenItIsUnauthorised()
      throws Exception {
    mockMvc.perform(get("/actuator/metrics/")).andExpect(status().isUnauthorized());
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

  /**
   * A tenant's Admin, the most a member of an organisation can be. Read across every tenant, the
   * counters would tell them what happened in organisations not their own.
   */
  @Test
  void GivenATenantAdminsToken_WhenScrapingPrometheus_ThenItIsForbidden() throws Exception {
    String token = aTenantAdminsToken();

    mockMvc
        .perform(TestTenants.with(get("/actuator/prometheus"), token))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenATenantAdminsToken_WhenReadingTheChangesCounter_ThenItIsForbidden() throws Exception {
    String token = aTenantAdminsToken();
    assertThat(meters.find("hrsched.changes").counters())
        .describedAs("arranged: onboarding the Admin's organisation records a change")
        .isNotEmpty();

    mockMvc
        .perform(TestTenants.with(get("/actuator/metrics/hrsched.changes"), token))
        .andExpect(status().isForbidden());
  }

  /** The operator administers tenants, not this service's instrumentation. */
  @Test
  void GivenTheOperatorsToken_WhenScrapingPrometheus_ThenItIsForbidden() throws Exception {
    String token = TestTenants.operatorToken(mockMvc);

    mockMvc
        .perform(TestTenants.with(get("/actuator/prometheus"), token))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenTheOperatorsToken_WhenReadingTheChangesCounter_ThenItIsForbidden() throws Exception {
    String token = TestTenants.operatorToken(mockMvc);
    onboardAnOrganisation();
    assertThat(meters.find("hrsched.changes").counters())
        .describedAs("arranged: onboarding records a change")
        .isNotEmpty();

    mockMvc
        .perform(TestTenants.with(get("/actuator/metrics/hrsched.changes"), token))
        .andExpect(status().isForbidden());
  }

  /**
   * What a scraper would be given once #78 lets one in. Read from the Prometheus registry itself,
   * because the meters reaching some registry does not prove they reach this one: a plain in-memory
   * registry satisfies every other check in this class. A request is served first, so the HTTP
   * timer has a sample to publish.
   */
  @Test
  void GivenARequestHasBeenServed_WhenThePrometheusRegistryIsScraped_ThenEveryMeterIsInIt()
      throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

    String scrape = prometheus.scrape();

    assertSoftly(
        softly ->
            List.of(
                    "jvm_memory_used_bytes",
                    "jvm_threads_live_threads",
                    "process_uptime_seconds",
                    "hrsched_shifts_required",
                    "hrsched_shifts_uncovered",
                    "http_server_requests_seconds")
                .forEach(
                    meter ->
                        softly
                            .assertThat(scrape)
                            .describedAs("the Prometheus scrape, for %s", meter)
                            .contains(meter)));
  }

  /**
   * The index of actuator's endpoints, which lists every exposed one, with links built from the
   * request's {@code Host} header, to anybody who asks. It names the metrics endpoints a caller is
   * refused, and the links point wherever the caller said the service lives.
   */
  @Test
  void GivenNoToken_WhenAskingForTheActuatorIndex_ThenItListsNoMetricsEndpoint() throws Exception {
    MockHttpServletResponse response = mockMvc.perform(get("/actuator")).andReturn().getResponse();

    assertNoMetricsEndpointIsListed(response);
  }

  @Test
  void GivenTheOperatorsToken_WhenAskingForTheActuatorIndex_ThenItListsNoMetricsEndpoint()
      throws Exception {
    String token = TestTenants.operatorToken(mockMvc);

    MockHttpServletResponse response =
        mockMvc.perform(TestTenants.with(get("/actuator"), token)).andReturn().getResponse();

    assertNoMetricsEndpointIsListed(response);
  }

  private static void assertNoMetricsEndpointIsListed(MockHttpServletResponse response)
      throws Exception {
    String body = response.getContentAsString();
    assertSoftly(
        softly -> {
          softly
              .assertThat(response.getStatus())
              .describedAs("status of the actuator index, which answered: %s", body)
              .isNotEqualTo(200);
          softly
              .assertThat(body)
              .describedAs("the actuator index")
              .doesNotContain(PROMETHEUS_ENDPOINT)
              .doesNotContain(METRICS_ENDPOINT);
        });
  }

  private String onboardAnOrganisation() throws Exception {
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

  private String aTenantAdminsToken() throws Exception {
    OrganisationId organisation = new OrganisationId(UUID.fromString(onboardAnOrganisation()));
    return TestTenants.tokenFor(mockMvc, organisation, Role.ADMIN);
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
