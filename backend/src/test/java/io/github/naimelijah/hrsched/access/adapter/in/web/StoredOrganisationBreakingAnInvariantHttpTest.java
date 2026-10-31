package io.github.naimelijah.hrsched.access.adapter.in.web;

import static io.github.naimelijah.hrsched.shared.testsupport.TestTenants.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An organisation stored breaking a rule the domain enforces is this system's defect, never the
 * Operator's mistake.
 *
 * <p>The schema's {@code organisation_name_not_blank} trims spaces only (V1), so a name that is
 * only a tab, written by hand, a restore or a bug, stores, and the rebuild through {@code
 * Organisation} refuses it as blank (R2). That refusal must not reach the handler that answers a
 * caller's own blank name with a 400 and no log line: Spring resolves a handler by an exception's
 * causes as well as its type, so the failure carries no domain exception at all. No handler takes
 * it, so it escapes the controller, which MockMvc surfaces as the exception itself.
 *
 * <p>The suite shares one database, and the Operator's listing reads every organisation in it, so
 * the test puts the name back before it ends: a blank name left behind would fail every later
 * listing.
 *
 * <p>Every name here is invented for the test.
 *
 * <p>Annotated identically to {@code OrganisationHttpTest}, so it shares the suite's Spring
 * context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class StoredOrganisationBreakingAnInvariantHttpTest {

  /** Blank to the domain's {@code isBlank}, but not to the schema's {@code btrim}. */
  private static final String BLANK_TO_THE_DOMAIN_ONLY = "\t";

  @Autowired private MockMvc mockMvc;

  /** Writes past the adapter, because no {@code Organisation} can carry a blank name. */
  private static void storeNameDirectly(String organisation, String name) {
    PostgresDatabase.jdbcClient()
        .sql("UPDATE organisation SET name = :name WHERE id = :id")
        .param("name", name)
        .param("id", UUID.fromString(organisation))
        .update();
  }

  @Test
  void GivenABlankOrganisationNameInTheStore_WhenTheOperatorReadsAndLists_ThenItFailsAsOurDefect()
      throws Exception {
    String operator = TestTenants.operatorToken(mockMvc);
    // Invented.
    String name = "Acme %s".formatted(UUID.randomUUID());
    String organisation =
        JsonPath.read(
            mockMvc
                .perform(
                    with(post("/api/organisations"), operator)
                        .content(TestTenants.onboarding(name)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");
    storeNameDirectly(organisation, BLANK_TO_THE_DOMAIN_ONLY);
    try {
      Throwable read =
          catchThrowable(
              () -> mockMvc.perform(with(get("/api/organisations/{id}", organisation), operator)));
      Throwable listing =
          catchThrowable(() -> mockMvc.perform(with(get("/api/organisations"), operator)));

      assertEscapesAsOurDefect(read, "reading it", organisation);
      assertEscapesAsOurDefect(listing, "listing the register", organisation);
    } finally {
      storeNameDirectly(organisation, name);
    }
  }

  private static void assertEscapesAsOurDefect(
      Throwable failure, String request, String organisation) {
    assertThat(failure)
        .describedAs(
            "%s was answered as the caller's mistake rather than escaping as our defect", request)
        .isNotNull()
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(organisation)
        .hasMessageContaining("R2")
        .hasMessageNotContaining(BLANK_TO_THE_DOMAIN_ONLY);
  }
}
