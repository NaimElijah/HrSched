package io.github.naimelijah.hrsched.access.adapter.in.web;

import static io.github.naimelijah.hrsched.access.adapter.in.web.AccessHttpRequests.as;
import static io.github.naimelijah.hrsched.access.adapter.in.web.AccessHttpRequests.asOperator;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives the organisation API over HTTP (requirement F13).
 *
 * <p>This is the one controller a tenant cannot reach at all for writes, and the one place where a
 * tenant actor can genuinely name an organisation that is not their own — the identity comes from
 * {@code X-Organisation} and the target from the path, so unlike every other endpoint the two can
 * differ, and the cross-tenant 404 is reachable over HTTP rather than only from a unit test.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrganisationHttpTest {

  @Autowired private MockMvc mockMvc;

  /**
   * @param name what to call it
   * @return the identifier of a freshly onboarded organisation
   */
  private String onboard(String name) throws Exception {
    String body =
        mockMvc
            .perform(
                asOperator(post("/api/organisations")).content("{\"name\":\"%s\"}".formatted(name)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  @Test
  void GivenTheOperator_WhenOnboardingAnOrganisation_ThenItIsCreatedAndAddressable()
      throws Exception {
    mockMvc
        .perform(asOperator(post("/api/organisations")).content("{\"name\":\"Acme\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.name").value("Acme"))
        .andExpect(jsonPath("$.id").isNotEmpty());
  }

  @Test
  void GivenAnOnboardedOrganisation_WhenTheOperatorLists_ThenItIsThere() throws Exception {
    String id = onboard("Meridian " + UUID.randomUUID());

    mockMvc
        .perform(asOperator(get("/api/organisations")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(id)).exists());
  }

  @Test
  void GivenAnOnboardedOrganisation_WhenTheOperatorReadsIt_ThenItIsReturned() throws Exception {
    String id = onboard("Acme");

    mockMvc
        .perform(asOperator(get("/api/organisations/" + id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
  }

  @Test
  void GivenAnOrganisation_WhenItsOwnAdminReadsIt_ThenItIsReturned() throws Exception {
    String id = onboard("Acme");

    mockMvc
        .perform(as(get("/api/organisations/" + id), id, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
  }

  /**
   * The refusal the requirements call a security incident. It is a 404 rather than a 403 because a
   * 403 would confirm that the other organisation exists.
   */
  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenReadingOurs_ThenItIsNotFound() throws Exception {
    String acme = onboard("Acme");
    String rival = onboard("Rival");

    mockMvc
        .perform(as(get("/api/organisations/" + acme), rival, "ADMIN"))
        .andExpect(status().isNotFound());
  }

  /**
   * Onboarding is exempt from the boundary check (ADR-020), so this reaches the use case and is
   * refused for the right reason even though the organisation claimed is not a real one.
   */
  @Test
  void GivenAnAdminRole_WhenOnboardingAnOrganisation_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(post("/api/organisations"), UUID.randomUUID().toString(), "ADMIN")
                .content("{\"name\":\"Acme\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenASchedulerRole_WhenListingOrganisations_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(as(get("/api/organisations"), onboard("Acme"), "SCHEDULER"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployeeRole_WhenListingOrganisations_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(as(get("/api/organisations"), onboard("Acme"), "EMPLOYEE"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenABlankName_WhenOnboarding_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(asOperator(post("/api/organisations")).content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoNameAtAll_WhenOnboarding_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(asOperator(post("/api/organisations")).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAMalformedBody_WhenOnboarding_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(asOperator(post("/api/organisations")).content("not json"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoActingUser_WhenOnboarding_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/organisations")
                .header("X-Role", "OPERATOR")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoSuchOrganisation_WhenTheOperatorReadsIt_ThenItIsNotFound() throws Exception {
    mockMvc
        .perform(asOperator(get("/api/organisations/" + UUID.randomUUID())))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenAnIdentifierThatIsNotAUuid_WhenReadingAnOrganisation_ThenItIsABadRequest()
      throws Exception {
    mockMvc
        .perform(asOperator(get("/api/organisations/not-a-uuid")))
        .andExpect(status().isBadRequest());
  }
}
