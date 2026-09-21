package io.github.naimelijah.hrsched.access.adapter.in.web;

import static io.github.naimelijah.hrsched.shared.testsupport.TestTenants.with;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives the organisation API over HTTP (requirement F13).
 *
 * <p><strong>The first routes to take their identity from a token</strong>, and the tests say so in
 * two ways. The last group asserts that the old identity headers are no longer believed here: a
 * request claiming {@code X-Role: OPERATOR} without a token is refused, and one carrying a tenant's
 * token alongside that header is refused as the tenant it actually is. Those two are the whole
 * point of the change, and they would pass vacuously if the routes had merely been left open.
 *
 * <p>Refusals here come from two different places and mean different things. A caller with no token
 * never reaches a handler: the chain answers 401. A caller with the wrong role is refused by the
 * URL rule with a 403. A tenant naming another organisation reaches the use case and is refused
 * with a 404, because a 403 would confirm it exists.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrganisationHttpTest {

  @Autowired private MockMvc mockMvc;

  private String operator() throws Exception {
    return TestTenants.operatorToken(mockMvc);
  }

  /**
   * @param name what to call it
   * @return the identifier of a freshly onboarded organisation
   */
  private String onboard(String name) throws Exception {
    String body =
        mockMvc
            .perform(
                with(post("/api/organisations"), operator())
                    .content("{\"name\":\"%s\"}".formatted(name)))
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
        .perform(with(post("/api/organisations"), operator()).content("{\"name\":\"Acme\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.name").value("Acme"));
  }

  @Test
  void GivenAnOnboardedOrganisation_WhenTheOperatorLists_ThenItIsThere() throws Exception {
    String id = onboard("Acme %s".formatted(UUID.randomUUID()));

    mockMvc
        .perform(with(get("/api/organisations"), operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(id)).exists());
  }

  @Test
  void GivenAnOnboardedOrganisation_WhenTheOperatorReadsIt_ThenItIsReturned() throws Exception {
    String id = onboard("Acme %s".formatted(UUID.randomUUID()));

    mockMvc
        .perform(with(get("/api/organisations/" + id), operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
  }

  @Test
  void GivenAnOrganisation_WhenItsOwnAdminReadsIt_ThenItIsReturned() throws Exception {
    String id = onboard("Acme %s".formatted(UUID.randomUUID()));
    String admin = TestTenants.tokenFor(mockMvc, organisation(id), Role.ADMIN);

    mockMvc
        .perform(with(get("/api/organisations/" + id), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
  }

  /** A 404 rather than a 403: saying otherwise would confirm that organisation exists. */
  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenReadingOurs_ThenItIsNotFound() throws Exception {
    String ours = onboard("Acme %s".formatted(UUID.randomUUID()));
    String theirs = onboard("Rival %s".formatted(UUID.randomUUID()));
    String stranger = TestTenants.tokenFor(mockMvc, organisation(theirs), Role.ADMIN);

    mockMvc
        .perform(with(get("/api/organisations/" + ours), stranger))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenATenantAdmin_WhenOnboarding_ThenItIsForbidden() throws Exception {
    String admin = TestTenants.tokenFor(mockMvc, organisation(onboard("Acme")), Role.ADMIN);

    mockMvc
        .perform(with(post("/api/organisations"), admin).content("{\"name\":\"Beta\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenATenantAdmin_WhenListingOrganisations_ThenItIsForbidden() throws Exception {
    String admin = TestTenants.tokenFor(mockMvc, organisation(onboard("Acme")), Role.ADMIN);

    mockMvc.perform(with(get("/api/organisations"), admin)).andExpect(status().isForbidden());
  }

  @Test
  void GivenASchedulerRole_WhenListingOrganisations_ThenItIsForbidden() throws Exception {
    String scheduler = TestTenants.tokenFor(mockMvc, organisation(onboard("Acme")), Role.SCHEDULER);

    mockMvc.perform(with(get("/api/organisations"), scheduler)).andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployeeRole_WhenListingOrganisations_ThenItIsForbidden() throws Exception {
    String employee = TestTenants.tokenFor(mockMvc, organisation(onboard("Acme")), Role.EMPLOYEE);

    mockMvc.perform(with(get("/api/organisations"), employee)).andExpect(status().isForbidden());
  }

  @Test
  void GivenNoToken_WhenOnboarding_ThenItIsUnauthorised() throws Exception {
    mockMvc
        .perform(
            post("/api/organisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void GivenNoToken_WhenListingOrganisations_ThenItIsUnauthorised() throws Exception {
    mockMvc.perform(get("/api/organisations")).andExpect(status().isUnauthorized());
  }

  /**
   * The headers are no longer believed on these routes, and this is the test that says so. Before
   * this change the same request onboarded an organisation.
   */
  @Test
  void GivenAnXRoleOperatorHeaderWithoutAToken_WhenOnboarding_ThenItIsUnauthorised()
      throws Exception {
    mockMvc
        .perform(
            post("/api/organisations")
                .header("X-User", UUID.randomUUID().toString())
                .header("X-Role", "OPERATOR")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\"}"))
        .andExpect(status().isUnauthorized());
  }

  /** Claiming a role in a header does not add one: the token decides, and it says tenant Admin. */
  @Test
  void GivenATenantTokenAndAnXRoleOperatorHeader_WhenOnboarding_ThenItIsForbidden()
      throws Exception {
    String admin = TestTenants.tokenFor(mockMvc, organisation(onboard("Acme")), Role.ADMIN);

    mockMvc
        .perform(
            with(post("/api/organisations"), admin)
                .header("X-Role", "OPERATOR")
                .content("{\"name\":\"Beta\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenABlankName_WhenOnboarding_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(with(post("/api/organisations"), operator()).content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoNameAtAll_WhenOnboarding_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(with(post("/api/organisations"), operator()).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAMalformedBody_WhenOnboarding_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(with(post("/api/organisations"), operator()).content("not json"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoSuchOrganisation_WhenTheOperatorReadsIt_ThenItIsNotFound() throws Exception {
    mockMvc
        .perform(with(get("/api/organisations/" + UUID.randomUUID()), operator()))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenAnIdentifierThatIsNotAUuid_WhenReadingAnOrganisation_ThenItIsABadRequest()
      throws Exception {
    mockMvc
        .perform(with(get("/api/organisations/not-a-uuid"), operator()))
        .andExpect(status().isBadRequest());
  }

  private static OrganisationId organisation(String id) {
    return new OrganisationId(UUID.fromString(id));
  }
}
