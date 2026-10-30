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
 * <p>The schema's {@code employee_name_not_blank} and {@code qualification_name_not_blank} trim
 * spaces only (V1), so a name that is only a tab, written by hand, a restore or a bug, stores, and
 * the rebuild through the aggregate refuses it as blank (R2). That refusal must not reach the
 * handler that answers a caller's own blank name with a 400 and no log line: Spring resolves a
 * handler by an exception's causes as well as its type, so the failure carries no domain exception
 * at all. No handler takes it, so it escapes the controller, which MockMvc surfaces as the
 * exception itself.
 *
 * <p>Overlapping unavailability was the breach this pinned until {@code V15} made it unstorable;
 * {@code JdbcEmployeeRepositoryTest} pins that the store refuses one.
 *
 * <p>Annotated identically to {@code EmployeeHttpTest}, so it shares the suite's Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class StoreBreakingAnInvariantHttpTest {

  /** Blank to the domain's {@code isBlank}, but not to the schema's {@code btrim}. */
  private static final String BLANK_TO_THE_DOMAIN_ONLY = "\t";

  @Autowired private MockMvc mockMvc;

  /** Writes past the adapter, because no {@code Employee} can carry a blank name. */
  private static void storeNameDirectly(String employee, String name) {
    PostgresDatabase.jdbcClient()
        .sql("UPDATE employee SET name = :name WHERE id = :id")
        .param("name", name)
        .param("id", UUID.fromString(employee))
        .update();
  }

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
    storeNameDirectly(ada, BLANK_TO_THE_DOMAIN_ONLY);

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

  /** {@code qualification_name_not_blank} trims spaces only too, and R2 covers qualifications. */
  @Test
  void GivenABlankQualificationNameInTheStore_WhenListingQualifications_ThenItFailsAsOurDefect()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String forklift =
        JsonPath.read(
            mockMvc
                .perform(
                    as(mockMvc, post("/api/qualifications"), organisation, "ADMIN")
                        .content("{\"name\":\"Forklift\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");
    PostgresDatabase.jdbcClient()
        .sql("UPDATE qualification SET name = :name WHERE id = :id")
        .param("name", BLANK_TO_THE_DOMAIN_ONLY)
        .param("id", UUID.fromString(forklift))
        .update();

    Throwable failure =
        catchThrowable(
            () -> mockMvc.perform(as(mockMvc, get("/api/qualifications"), organisation, "ADMIN")));

    assertThat(failure)
        .describedAs("answered as the caller's mistake rather than escaping as our defect")
        .isNotNull()
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(forklift)
        .hasMessageContaining("R2");
  }
}
