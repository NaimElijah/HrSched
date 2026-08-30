package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.as;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.asThemself;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives the employee API over HTTP: who may administer people, who may speak for themselves, and
 * what a request has to look like to be accepted at all.
 *
 * <p>The scheduling side of the hexagon is covered by {@code RosteringHttpTest}; this is the other
 * half of the API, and the half where self-service makes the acting identity load-bearing rather
 * than decorative.
 *
 * <p>Annotated identically to {@code RosteringHttpTest} on purpose. A differing annotation set
 * would give this class its own Spring context cache key and pay a second full startup for nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmployeeHttpTest {

  @Autowired private MockMvc mockMvc;

  private String addEmployee(String organisation, String name) throws Exception {
    String body =
        mockMvc
            .perform(
                as(post("/api/employees"), organisation, "ADMIN")
                    .content("{\"name\":\"%s\"}".formatted(name)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private String defineQualification(String organisation, String name) throws Exception {
    String body =
        mockMvc
            .perform(
                as(post("/api/qualifications"), organisation, "ADMIN")
                    .content("{\"name\":\"%s\"}".formatted(name)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  @Test
  void GivenAnAdmin_WhenAddingAnEmployee_ThenTheyAreCreatedAndListed() throws Exception {
    String organisation = anOrganisation();

    mockMvc
        .perform(as(post("/api/employees"), organisation, "ADMIN").content("{\"name\":\"Ada\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.name").value("Ada"));

    mockMvc
        .perform(as(get("/api/employees"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Ada"));
  }

  @Test
  void GivenASchedulerRole_WhenAddingAnEmployee_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(post("/api/employees"), anOrganisation(), "SCHEDULER").content("{\"name\":\"Ada\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployeeRole_WhenListingColleagues_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(as(get("/api/employees"), anOrganisation(), "EMPLOYEE"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAScheduler_WhenListingEmployees_ThenTheyAreReturned() throws Exception {
    String organisation = anOrganisation();
    addEmployee(organisation, "Ada");

    mockMvc
        .perform(as(get("/api/employees"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void GivenPeopleInAnotherOrganisation_WhenListing_ThenOnlyOurOwnAreReturned() throws Exception {
    String ours = anOrganisation();
    addEmployee(ours, "Ada");
    addEmployee(anOrganisation(), "Their Employee");

    mockMvc
        .perform(as(get("/api/employees"), ours, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Ada"));
  }

  @Test
  void GivenAnOperator_WhenListingAnotherOrganisation_ThenItIsPermitted() throws Exception {
    String theirs = anOrganisation();
    addEmployee(theirs, "Ada");

    mockMvc
        .perform(as(get("/api/employees"), theirs, "OPERATOR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void GivenAnAdmin_WhenGrantingAQualification_ThenTheEmployeeHoldsIt() throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");
    String forklift = defineQualification(organisation, "Forklift");

    mockMvc
        .perform(
            as(put("/api/employees/" + ada + "/qualifications/" + forklift), organisation, "ADMIN"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(as(get("/api/employees"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].qualifications.length()").value(1))
        .andExpect(jsonPath("$[0].qualifications[0]").value(forklift));
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenGrantingAQualification_ThenItIsNotFound()
      throws Exception {
    String theirs = anOrganisation();
    String theirEmployee = addEmployee(theirs, "Their Employee");
    String ours = anOrganisation();
    String forklift = defineQualification(ours, "Forklift");

    mockMvc
        .perform(
            as(
                put("/api/employees/" + theirEmployee + "/qualifications/" + forklift),
                ours,
                "ADMIN"))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenAQualificationOfAnotherOrganisation_WhenGrantingIt_ThenItIsNotFound() throws Exception {
    String ours = anOrganisation();
    String ada = addEmployee(ours, "Ada");
    String theirQualification = defineQualification(anOrganisation(), "Forklift");

    mockMvc
        .perform(
            as(
                put("/api/employees/" + ada + "/qualifications/" + theirQualification),
                ours,
                "ADMIN"))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenAnUnknownEmployee_WhenGrantingAQualification_ThenItIsNotFound() throws Exception {
    String organisation = anOrganisation();
    String forklift = defineQualification(organisation, "Forklift");

    mockMvc
        .perform(
            as(
                put("/api/employees/" + UUID.randomUUID() + "/qualifications/" + forklift),
                organisation,
                "ADMIN"))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenAnAdmin_WhenSettingContractTerms_ThenItIsAccepted() throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":2400}"))
        .andExpect(status().isNoContent());
  }

  @Test
  void GivenZeroContractedMinutes_WhenSettingContractTerms_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":0}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * {@code weeklyContractedMinutes} is a primitive {@code long}, which under Jackson 2 would have
   * defaulted an absent field to zero and reported it back as a value that is not positive -- a
   * caller told the number they never sent is invalid. Jackson 3 refuses to construct the record
   * instead, so the absent case and the explicit-zero case already read differently. Pinned here
   * because the distinction is the deserialiser's, not this codebase's, and an upgrade could take
   * it away without touching a line of our own.
   */
  @Test
  void GivenNoContractedMinutes_WhenSettingContractTerms_ThenTheBodyIsReportedAsIncomplete()
      throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(put("/api/employees/" + ada + "/contract"), organisation, "ADMIN").content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Malformed request"))
        .andExpect(jsonPath("$.detail").value(containsString("missing required fields")));
  }

  @Test
  void GivenZeroContractedMinutesExplicitly_WhenSettingContractTerms_ThenItIsReportedAsInvalid()
      throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("positive")));
  }

  @Test
  void GivenTheEmployeeThemself_WhenDeclaringUnavailability_ThenItIsAccepted() throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            asThemself(post("/api/employees/" + ada + "/unavailability"), organisation, ada)
                .content("{\"start\":\"2026-11-02T00:00:00Z\",\"end\":\"2026-11-03T00:00:00Z\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  void GivenAColleague_WhenDeclaringSomebodyElsesUnavailability_ThenItIsForbidden()
      throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");

    mockMvc
        .perform(
            asThemself(post("/api/employees/" + ada + "/unavailability"), organisation, grace)
                .content("{\"start\":\"2026-11-04T00:00:00Z\",\"end\":\"2026-11-05T00:00:00Z\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnOverlappingSpan_WhenDeclaringUnavailability_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            as(post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-06T00:00:00Z\",\"end\":\"2026-11-08T00:00:00Z\"}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            as(post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-07T00:00:00Z\",\"end\":\"2026-11-09T00:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAnEndBeforeItsStart_WhenDeclaringUnavailability_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-10T00:00:00Z\",\"end\":\"2026-11-09T00:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoEnd_WhenDeclaringUnavailability_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-11T00:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenTheEmployeeThemself_WhenStatingAPreference_ThenItIsAccepted() throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            asThemself(post("/api/employees/" + ada + "/preferences"), organisation, ada)
                .content(
                    "{\"start\":\"2026-11-12T08:00:00Z\",\"end\":\"2026-11-12T16:00:00Z\","
                        + "\"strength\":\"PREFERS\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  void GivenNoStrength_WhenStatingAPreference_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(post("/api/employees/" + ada + "/preferences"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-13T08:00:00Z\",\"end\":\"2026-11-13T16:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoName_WhenAddingAnEmployee_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(as(post("/api/employees"), anOrganisation(), "ADMIN").content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenABlankName_WhenAddingAnEmployee_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(as(post("/api/employees"), anOrganisation(), "ADMIN").content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAMalformedBody_WhenAddingAnEmployee_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(as(post("/api/employees"), anOrganisation(), "ADMIN").content("{\"name\":"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAnUnknownRole_WhenAddingAnEmployee_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(post("/api/employees"), anOrganisation(), "SUPERVISOR")
                .content("{\"name\":\"Ada\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenANonUuidOrganisation_WhenListingEmployees_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(as(get("/api/employees"), "not-a-uuid", "ADMIN"))
        .andExpect(status().isBadRequest());
  }
}
