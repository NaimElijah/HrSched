package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Stored rows breaking a rule the domain enforces are this system's defect, never the caller's
 * mistake.
 *
 * <p>The schema does not refuse overlapping unavailability (V1, ADR-004), so a row written by hand,
 * a restore or a bug can break R7, and the rebuild through the aggregate refuses it. That refusal
 * must not reach the handler that answers a caller's own overlapping declaration with a 400 and no
 * log line: Spring resolves a handler by an exception's causes as well as its type, so the failure
 * carries no domain exception at all. No handler takes it, so it escapes the controller, which
 * MockMvc surfaces as the exception itself.
 *
 * <p>Every instant below is invented.
 *
 * <p>Annotated identically to {@code EmployeeHttpTest}, so it shares the suite's Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class StoreBreakingAnInvariantHttpTest {

  @Autowired private MockMvc mockMvc;

  /** Writes past the adapter, because no {@code Employee} can carry what this stores. */
  private static void storeUnavailabilityDirectly(
      String employee, int ordinal, String start, String end) {
    PostgresDatabase.jdbcClient()
        .sql(
            """
            INSERT INTO employee_unavailability (employee_id, ordinal, starts_at, ends_at)
            VALUES (:id, :ordinal, :startsAt, :endsAt)
            """)
        .param("id", UUID.fromString(employee))
        .param("ordinal", ordinal)
        .param("startsAt", Instant.parse(start).atOffset(ZoneOffset.UTC))
        .param("endsAt", Instant.parse(end).atOffset(ZoneOffset.UTC))
        .update();
  }

  @Test
  void GivenOverlappingUnavailabilityInTheStore_WhenListingEmployees_ThenItFailsAsOurDefect()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada =
        JsonPath.read(
            mockMvc
                .perform(
                    as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                        .content("{\"name\":\"Ada\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");
    // Invented: a fortnight, and a week overlapping its last day.
    storeUnavailabilityDirectly(ada, 0, "2026-08-01T00:00:00Z", "2026-08-15T00:00:00Z");
    storeUnavailabilityDirectly(ada, 1, "2026-08-14T00:00:00Z", "2026-08-21T00:00:00Z");

    Throwable failure =
        catchThrowable(
            () -> mockMvc.perform(as(mockMvc, get("/api/employees"), organisation, "ADMIN")));

    assertThat(failure)
        .describedAs("answered as the caller's mistake rather than escaping as our defect")
        .isNotNull()
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ada)
        .hasMessageContaining("R7")
        .hasMessageNotContaining("Ada")
        .hasMessageNotContaining("2026-08");
  }

  /**
   * The schema's {@code btrim} trims spaces only, so a name that is only a tab stores, while the
   * domain refuses every blank name (R2) and answers a caller sending one with a 400.
   */
  @Test
  void GivenABlankNameInTheStore_WhenListingEmployees_ThenItFailsAsOurDefect() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada =
        JsonPath.read(
            mockMvc
                .perform(
                    as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                        .content("{\"name\":\"Ada\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");
    PostgresDatabase.jdbcClient()
        .sql("UPDATE employee SET name = :name WHERE id = :id")
        .param("name", "\t")
        .param("id", UUID.fromString(ada))
        .update();

    Throwable failure =
        catchThrowable(
            () -> mockMvc.perform(as(mockMvc, get("/api/employees"), organisation, "ADMIN")));

    assertThat(failure)
        .describedAs("answered as the caller's mistake rather than escaping as our defect")
        .isNotNull()
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ada)
        .hasMessageContaining("R2");
  }
}
