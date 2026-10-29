package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.as;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.asThemself;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.http.MediaType;
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
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class EmployeeHttpTest {

  @Autowired private MockMvc mockMvc;

  private String addEmployee(String organisation, String name) throws Exception {
    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/employees"), organisation, "ADMIN")
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
                as(mockMvc, post("/api/qualifications"), organisation, "ADMIN")
                    .content("{\"name\":\"%s\"}".formatted(name)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  /**
   * Requirement F4. Until the listing carried these, only the use case satisfied that row and the
   * HTTP surface quietly did not — a Scheduler could see who worked there but not when they could
   * not work, which is the half of it that decides a roster.
   */
  @Test
  void GivenSomebodyWithAbsenceAndAContract_WhenListed_ThenTheirAvailabilityIsCarriedToo()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada =
        JsonPath.read(
            mockMvc
                .perform(
                    as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                        .content("{\"name\":\"Ada Lovelace\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.id");
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-10-07T00:00:00Z\",\"end\":\"2026-10-08T00:00:00Z\"}"))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":2100}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(as(mockMvc, get("/api/employees"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].unavailability.length()").value(1))
        .andExpect(jsonPath("$[0].unavailability[0].start").value("2026-10-07T00:00:00Z"))
        .andExpect(jsonPath("$[0].unavailability[0].end").value("2026-10-08T00:00:00Z"))
        .andExpect(jsonPath("$[0].weeklyContractedMinutes").value(2100));
  }

  @Test
  void GivenSomebodyJustAdded_WhenListed_ThenTheyHaveNoAbsenceAndNoContract() throws Exception {
    String organisation = anOrganisation(mockMvc);
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                .content("{\"name\":\"Ada Lovelace\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.unavailability.length()").value(0))
        .andExpect(jsonPath("$.weeklyContractedMinutes").doesNotExist());

    mockMvc
        .perform(as(mockMvc, get("/api/employees"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].unavailability.length()").value(0));
  }

  @Test
  void GivenAnAdmin_WhenAddingAnEmployee_ThenTheyAreCreatedAndListed() throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                .content("{\"name\":\"Ada\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.name").value("Ada"));

    mockMvc
        .perform(as(mockMvc, get("/api/employees"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Ada"));
  }

  @Test
  void GivenASchedulerRole_WhenAddingAnEmployee_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees"), anOrganisation(mockMvc), "SCHEDULER")
                .content("{\"name\":\"Ada\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployeeRole_WhenListingColleagues_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(as(mockMvc, get("/api/employees"), anOrganisation(mockMvc), "EMPLOYEE"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAScheduler_WhenListingEmployees_ThenTheyAreReturned() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");

    mockMvc
        .perform(as(mockMvc, get("/api/employees"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void GivenPeopleInAnotherOrganisation_WhenListing_ThenOnlyOurOwnAreReturned() throws Exception {
    String ours = anOrganisation(mockMvc);
    addEmployee(ours, "Ada");
    addEmployee(anOrganisation(mockMvc), "Their Employee");

    mockMvc
        .perform(as(mockMvc, get("/api/employees"), ours, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Ada"));
  }

  /** Flipped by ADR-030: a staff list is a tenant's, and the operator is not a member of one. */
  @Test
  void GivenTheOperator_WhenListingAnOrganisationsEmployees_ThenItIsForbidden() throws Exception {
    String theirs = anOrganisation(mockMvc);
    addEmployee(theirs, "Ada");

    mockMvc
        .perform(as(mockMvc, get("/api/employees"), theirs, "OPERATOR"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnAdmin_WhenGrantingAQualification_ThenTheEmployeeHoldsIt() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String forklift = defineQualification(organisation, "Forklift");

    mockMvc
        .perform(
            as(
                mockMvc,
                put("/api/employees/" + ada + "/qualifications/" + forklift),
                organisation,
                "ADMIN"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(as(mockMvc, get("/api/employees"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].qualifications.length()").value(1))
        .andExpect(jsonPath("$[0].qualifications[0]").value(forklift));
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenGrantingAQualification_ThenItIsNotFound()
      throws Exception {
    String theirs = anOrganisation(mockMvc);
    String theirEmployee = addEmployee(theirs, "Their Employee");
    String ours = anOrganisation(mockMvc);
    String forklift = defineQualification(ours, "Forklift");

    mockMvc
        .perform(
            as(
                mockMvc,
                put("/api/employees/" + theirEmployee + "/qualifications/" + forklift),
                ours,
                "ADMIN"))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenAQualificationOfAnotherOrganisation_WhenGrantingIt_ThenItIsNotFound() throws Exception {
    String ours = anOrganisation(mockMvc);
    String ada = addEmployee(ours, "Ada");
    String theirQualification = defineQualification(anOrganisation(mockMvc), "Forklift");

    mockMvc
        .perform(
            as(
                mockMvc,
                put("/api/employees/" + ada + "/qualifications/" + theirQualification),
                ours,
                "ADMIN"))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenAnUnknownEmployee_WhenGrantingAQualification_ThenItIsNotFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String forklift = defineQualification(organisation, "Forklift");

    mockMvc
        .perform(
            as(
                mockMvc,
                put("/api/employees/" + UUID.randomUUID() + "/qualifications/" + forklift),
                organisation,
                "ADMIN"))
        .andExpect(status().isNotFound());
  }

  /**
   * The security chain admits every tenant role to {@code /api/employees/**}, so the use case is
   * all that stops somebody granting themself a qualification a shift then requires.
   */
  @Test
  void GivenTheEmployeeThemself_WhenGrantingThemselvesAQualification_ThenItIsForbiddenAndNotHeld()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String forklift = defineQualification(organisation, "Forklift");

    mockMvc
        .perform(
            asThemself(
                mockMvc,
                put("/api/employees/" + ada + "/qualifications/" + forklift),
                organisation,
                ada))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(as(mockMvc, get("/api/employees"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].qualifications.length()").value(0));
  }

  @Test
  void GivenAnAdmin_WhenSettingContractTerms_ThenItIsAccepted() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":2400}"))
        .andExpect(status().isNoContent());
  }

  /**
   * The chain admits every tenant role to {@code /api/employees/**}, so the use case is all that
   * stops somebody rewriting their own contract. Both figures are invented: contracted minutes are
   * a term of an individual agreement, never a statute's.
   */
  @Test
  void GivenTheEmployeeThemself_WhenSettingTheirOwnContract_ThenItIsForbiddenAndUnchanged()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":1380}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            asThemself(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, ada)
                .content("{\"weeklyContractedMinutes\":570}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(as(mockMvc, get("/api/employees/" + ada), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.weeklyContractedMinutes").value(1380));
  }

  @Test
  void GivenZeroContractedMinutes_WhenSettingContractTerms_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
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
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Malformed request"))
        .andExpect(jsonPath("$.detail").value(containsString("missing required fields")));
  }

  @Test
  void GivenZeroContractedMinutesExplicitly_WhenSettingContractTerms_ThenItIsReportedAsInvalid()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("positive")));
  }

  /**
   * Contracted hours say things about a household, and a problem detail is copied wherever the
   * caller logs it. The invented -1234 minutes is -20 hours 34 minutes; neither figure comes back.
   */
  @Test
  void GivenNegativeContractedMinutes_WhenSettingContractTerms_ThenTheRefusalRepeatsNoHours()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":-1234}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").isNotEmpty())
        .andExpect(jsonPath("$.detail").value(not(containsString("1234"))))
        .andExpect(jsonPath("$.detail").value(not(containsString("20"))));
  }

  @Test
  void GivenTheEmployeeThemself_WhenDeclaringUnavailability_ThenItIsAccepted() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            asThemself(
                    mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, ada)
                .content("{\"start\":\"2026-11-02T00:00:00Z\",\"end\":\"2026-11-03T00:00:00Z\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  void GivenAColleague_WhenDeclaringSomebodyElsesUnavailability_ThenItIsForbidden()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");

    mockMvc
        .perform(
            asThemself(
                    mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, grace)
                .content("{\"start\":\"2026-11-04T00:00:00Z\",\"end\":\"2026-11-05T00:00:00Z\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAColleague_WhenStatingSomebodyElsesPreference_ThenItIsForbiddenAndNothingIsStated()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");

    mockMvc
        .perform(
            asThemself(mockMvc, post("/api/employees/" + ada + "/preferences"), organisation, grace)
                .content(
                    "{\"start\":\"2026-11-04T08:00:00Z\",\"end\":\"2026-11-04T16:00:00Z\","
                        + "\"strength\":\"PREFERS_NOT\"}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(as(mockMvc, get("/api/employees/" + ada + "/preferences"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void GivenAnOverlappingSpan_WhenDeclaringUnavailability_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-06T00:00:00Z\",\"end\":\"2026-11-08T00:00:00Z\"}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-07T00:00:00Z\",\"end\":\"2026-11-09T00:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * One of the two spans is an absence already stored, and the detail would hand it to whoever sent
   * the second. Every invented instant here falls on 2026-11-20 to 2026-11-23, so no day of that
   * month's twenties may appear.
   */
  @Test
  void GivenAnOverlappingSpan_WhenDeclaringUnavailability_ThenTheRefusalRepeatsNeitherSpan()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    declareUnavailability(organisation, ada, "2026-11-20T00:00:00Z", "2026-11-22T00:00:00Z");

    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-21T00:00:00Z\",\"end\":\"2026-11-23T00:00:00Z\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").isNotEmpty())
        .andExpect(jsonPath("$.detail").value(not(containsString("2026-11-2"))));
  }

  @Test
  void GivenAnEndBeforeItsStart_WhenDeclaringUnavailability_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-10T00:00:00Z\",\"end\":\"2026-11-09T00:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoEnd_WhenDeclaringUnavailability_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-11T00:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenTheEmployeeThemself_WhenStatingAPreference_ThenItIsAccepted() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            asThemself(mockMvc, post("/api/employees/" + ada + "/preferences"), organisation, ada)
                .content(
                    "{\"start\":\"2026-11-12T08:00:00Z\",\"end\":\"2026-11-12T16:00:00Z\","
                        + "\"strength\":\"PREFERS\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  void GivenNoStrength_WhenStatingAPreference_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/preferences"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-13T08:00:00Z\",\"end\":\"2026-11-13T16:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoName_WhenAddingAnEmployee_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees"), anOrganisation(mockMvc), "ADMIN").content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenABlankName_WhenAddingAnEmployee_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees"), anOrganisation(mockMvc), "ADMIN")
                .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAMalformedBody_WhenAddingAnEmployee_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees"), anOrganisation(mockMvc), "ADMIN")
                .content("{\"name\":"))
        .andExpect(status().isBadRequest());
  }

  /**
   * A 201 says where the new resource lives. For as long as nothing answered that address, the one
   * link the API handed out was the one link that led nowhere.
   */
  @Test
  void GivenAnAddedEmployee_WhenFollowingTheirLocation_ThenTheyAreFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String location =
        mockMvc
            .perform(
                as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                    .content("{\"name\":\"Ada\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader("Location");

    mockMvc
        .perform(as(mockMvc, get(location), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Ada"))
        .andExpect(jsonPath("$.qualifications.length()").value(0))
        .andExpect(jsonPath("$.unavailability.length()").value(0));
  }

  /** Reading one person is restricted exactly as listing them is: it carries the same absences. */
  @Test
  void GivenAnEmployeeRole_WhenReadingSomebodyByIdentity_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(as(mockMvc, get("/api/employees/" + ada), organisation, "EMPLOYEE"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenReadThroughOurs_ThenThereIsNothingThere()
      throws Exception {
    String theirs = addEmployee(anOrganisation(mockMvc), "Mole");

    mockMvc
        .perform(as(mockMvc, get("/api/employees/" + theirs), anOrganisation(mockMvc), "ADMIN"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  // --- F6: reading back what somebody declared ----------------------------------------------

  @Test
  void GivenTheEmployeeThemself_WhenReadingBackTheirUnavailability_ThenWhatTheyDeclaredIsListed()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            asThemself(
                    mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, ada)
                .content("{\"start\":\"2026-11-14T00:00:00Z\",\"end\":\"2026-11-15T00:00:00Z\"}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            asThemself(
                mockMvc, get("/api/employees/" + ada + "/unavailability"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].start").value("2026-11-14T00:00:00Z"))
        .andExpect(jsonPath("$[0].end").value("2026-11-15T00:00:00Z"));
  }

  @Test
  void GivenTheEmployeeThemself_WhenReadingBackTheirPreferences_ThenWhatTheyStatedIsListed()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            asThemself(mockMvc, post("/api/employees/" + ada + "/preferences"), organisation, ada)
                .content(
                    "{\"start\":\"2026-11-16T08:00:00Z\",\"end\":\"2026-11-16T16:00:00Z\","
                        + "\"strength\":\"PREFERS_NOT\"}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            asThemself(mockMvc, get("/api/employees/" + ada + "/preferences"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].start").value("2026-11-16T08:00:00Z"))
        .andExpect(jsonPath("$[0].end").value("2026-11-16T16:00:00Z"))
        .andExpect(jsonPath("$[0].strength").value("PREFERS_NOT"));
  }

  @Test
  void GivenAScheduler_WhenReadingSomebodysPreferences_ThenTheyAreListed() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/preferences"), organisation, "ADMIN")
                .content(
                    "{\"start\":\"2026-11-17T08:00:00Z\",\"end\":\"2026-11-17T16:00:00Z\","
                        + "\"strength\":\"PREFERS\"}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            as(mockMvc, get("/api/employees/" + ada + "/preferences"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].strength").value("PREFERS"));
  }

  @Test
  void GivenAColleague_WhenReadingSomebodyElsesUnavailability_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");

    mockMvc
        .perform(
            asThemself(
                mockMvc, get("/api/employees/" + ada + "/unavailability"), organisation, grace))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAColleague_WhenReadingSomebodyElsesPreferences_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");

    mockMvc
        .perform(
            asThemself(mockMvc, get("/api/employees/" + ada + "/preferences"), organisation, grace))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenReadingTheirUnavailabilityThroughOurs_ThenNotFound()
      throws Exception {
    String theirs = addEmployee(anOrganisation(mockMvc), "Mole");

    mockMvc
        .perform(
            as(
                mockMvc,
                get("/api/employees/" + theirs + "/unavailability"),
                anOrganisation(mockMvc),
                "ADMIN"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenReadingTheirPreferencesThroughOurs_ThenNotFound()
      throws Exception {
    String theirs = addEmployee(anOrganisation(mockMvc), "Mole");

    mockMvc
        .perform(
            as(
                mockMvc,
                get("/api/employees/" + theirs + "/preferences"),
                anOrganisation(mockMvc),
                "ADMIN"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  // --- F26: withdrawing a declaration (ADR-040) ---------------------------------------------

  private void declareUnavailability(String organisation, String employee, String start, String end)
      throws Exception {
    mockMvc
        .perform(
            as(
                    mockMvc,
                    post("/api/employees/" + employee + "/unavailability"),
                    organisation,
                    "ADMIN")
                .content("{\"start\":\"%s\",\"end\":\"%s\"}".formatted(start, end)))
        .andExpect(status().isNoContent());
  }

  @Test
  void GivenTheEmployeeThemself_WhenWithdrawingTheirUnavailability_ThenItIsNoLongerListed()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    declareUnavailability(organisation, ada, "2026-11-18T00:00:00Z", "2026-11-19T00:00:00Z");

    mockMvc
        .perform(
            asThemself(
                mockMvc,
                delete("/api/employees/" + ada + "/unavailability")
                    .param("start", "2026-11-18T00:00:00Z")
                    .param("end", "2026-11-19T00:00:00Z"),
                organisation,
                ada))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            asThemself(
                mockMvc, get("/api/employees/" + ada + "/unavailability"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /**
   * PostgreSQL keeps microseconds, so a span sent with nanoseconds is stored a little different
   * from what was sent. It is named by the instants the read-back returns, which is what ADR-040
   * tells a client to send back, and not by the ones first sent.
   */
  @Test
  void GivenASpanDeclaredBeyondMicroseconds_WhenWithdrawing_ThenTheReadBackInstantsNameIt()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String sentStart = "2026-12-01T08:00:00.123456789Z";
    String sentEnd = "2026-12-01T16:00:00.987654321Z";
    declareUnavailability(organisation, ada, sentStart, sentEnd);
    String readBack =
        mockMvc
            .perform(
                as(
                    mockMvc,
                    get("/api/employees/" + ada + "/unavailability"),
                    organisation,
                    "ADMIN"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String storedStart = JsonPath.read(readBack, "$[0].start");
    String storedEnd = JsonPath.read(readBack, "$[0].end");

    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/employees/" + ada + "/unavailability")
                    .param("start", sentStart)
                    .param("end", sentEnd),
                organisation,
                "ADMIN"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/employees/" + ada + "/unavailability")
                    .param("start", storedStart)
                    .param("end", storedEnd),
                organisation,
                "ADMIN"))
        .andExpect(status().isNoContent());
  }

  @Test
  void GivenTheEmployeeThemself_WhenWithdrawingAPreference_ThenItIsNoLongerListed()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            asThemself(mockMvc, post("/api/employees/" + ada + "/preferences"), organisation, ada)
                .content(
                    "{\"start\":\"2026-11-20T08:00:00Z\",\"end\":\"2026-11-20T16:00:00Z\","
                        + "\"strength\":\"PREFERS\"}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            asThemself(
                mockMvc,
                delete("/api/employees/" + ada + "/preferences")
                    .param("start", "2026-11-20T08:00:00Z")
                    .param("end", "2026-11-20T16:00:00Z")
                    .param("strength", "PREFERS"),
                organisation,
                ada))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            asThemself(mockMvc, get("/api/employees/" + ada + "/preferences"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void GivenAColleague_WhenWithdrawingSomebodyElsesUnavailability_ThenItIsForbidden()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");
    declareUnavailability(organisation, ada, "2026-11-21T00:00:00Z", "2026-11-22T00:00:00Z");

    mockMvc
        .perform(
            asThemself(
                mockMvc,
                delete("/api/employees/" + ada + "/unavailability")
                    .param("start", "2026-11-21T00:00:00Z")
                    .param("end", "2026-11-22T00:00:00Z"),
                organisation,
                grace))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAColleague_WhenWithdrawingSomebodyElsesPreference_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");

    mockMvc
        .perform(
            asThemself(
                mockMvc,
                delete("/api/employees/" + ada + "/preferences")
                    .param("start", "2026-11-23T08:00:00Z")
                    .param("end", "2026-11-23T16:00:00Z")
                    .param("strength", "PREFERS"),
                organisation,
                grace))
        .andExpect(status().isForbidden());
  }

  @Test
  void
      GivenAnEmployeeOfAnotherOrganisation_WhenWithdrawingTheirUnavailabilityThroughOurs_ThenNotFound()
          throws Exception {
    String theirOrganisation = anOrganisation(mockMvc);
    String theirs = addEmployee(theirOrganisation, "Mole");
    declareUnavailability(
        theirOrganisation, theirs, "2026-11-24T00:00:00Z", "2026-11-25T00:00:00Z");

    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/employees/" + theirs + "/unavailability")
                    .param("start", "2026-11-24T00:00:00Z")
                    .param("end", "2026-11-25T00:00:00Z"),
                anOrganisation(mockMvc),
                "ADMIN"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenWithdrawingTheirPreferenceThroughOurs_ThenNotFound()
      throws Exception {
    String theirOrganisation = anOrganisation(mockMvc);
    String theirs = addEmployee(theirOrganisation, "Mole");
    mockMvc
        .perform(
            as(
                    mockMvc,
                    post("/api/employees/" + theirs + "/preferences"),
                    theirOrganisation,
                    "ADMIN")
                .content(
                    "{\"start\":\"2026-11-30T08:00:00Z\",\"end\":\"2026-11-30T16:00:00Z\","
                        + "\"strength\":\"PREFERS\"}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/employees/" + theirs + "/preferences")
                    .param("start", "2026-11-30T08:00:00Z")
                    .param("end", "2026-11-30T16:00:00Z")
                    .param("strength", "PREFERS"),
                anOrganisation(mockMvc),
                "ADMIN"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void GivenAnEndBeforeItsStart_WhenWithdrawingUnavailability_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/employees/" + ada + "/unavailability")
                    .param("start", "2026-12-02T16:00:00Z")
                    .param("end", "2026-12-02T08:00:00Z"),
                organisation,
                "ADMIN"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAnUnknownStrength_WhenWithdrawingAPreference_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/employees/" + ada + "/preferences")
                    .param("start", "2026-12-03T08:00:00Z")
                    .param("end", "2026-12-03T16:00:00Z")
                    .param("strength", "ADORES"),
                organisation,
                "ADMIN"))
        .andExpect(status().isBadRequest());
  }

  /** The detail names no span: a span says when somebody cannot work. */
  @Test
  void GivenASpanNeverDeclared_WhenWithdrawingIt_ThenItIsNotFoundWithoutRepeatingTheSpan()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    declareUnavailability(organisation, ada, "2026-11-26T00:00:00Z", "2026-11-27T00:00:00Z");

    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/employees/" + ada + "/unavailability")
                    .param("start", "2026-11-26T00:00:00Z")
                    .param("end", "2026-11-26T12:00:00Z"),
                organisation,
                "ADMIN"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").value(not(containsString("2026-11-26"))));
  }

  @Test
  void GivenNoEnd_WhenWithdrawingUnavailability_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/employees/" + ada + "/unavailability")
                    .param("start", "2026-11-28T00:00:00Z"),
                organisation,
                "ADMIN"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoStrength_WhenWithdrawingAPreference_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");

    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/employees/" + ada + "/preferences")
                    .param("start", "2026-11-29T08:00:00Z")
                    .param("end", "2026-11-29T16:00:00Z"),
                organisation,
                "ADMIN"))
        .andExpect(status().isBadRequest());
  }

  /**
   * The headers are no longer believed here, and these three are the tests that say so. Before this
   * change the first request added an employee, the second added one as an Admin, and the third
   * read somebody else's schedule.
   */
  @Test
  void GivenTheIdentityHeadersWithoutAToken_WhenAddingAnEmployee_ThenItIsUnauthorised()
      throws Exception {
    mockMvc
        .perform(
            post("/api/employees")
                .header("X-Organisation", anOrganisation(mockMvc))
                .header("X-User", UUID.randomUUID().toString())
                .header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ada Lovelace\"}"))
        .andExpect(status().isUnauthorized());
  }

  /** Claiming a role in a header does not add one: the token decides, and it says EMPLOYEE. */
  @Test
  void GivenAnEmployeeTokenAndAnXRoleAdminHeader_WhenAddingAnEmployee_ThenItIsForbidden()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, post("/api/employees"), organisation, "EMPLOYEE")
                .header("X-Role", "ADMIN")
                .content("{\"name\":\"Ada Lovelace\"}"))
        .andExpect(status().isForbidden());
  }

  /**
   * Claiming somebody else's personnel record does not become them. The link is carried by the
   * account the token names (ADR-012), so the header is read by nothing and the request is answered
   * for whoever is really signed in.
   */
  @Test
  void
      GivenAnEmployeeTokenAndAnXEmployeeHeaderNamingSomebodyElse_WhenReadingAPerson_ThenItIsStillTheirOwnRefusal()
          throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada Lovelace");
    String grace = addEmployee(organisation, "Grace Hopper");

    mockMvc
        .perform(
            asThemself(mockMvc, get("/api/employees/" + grace), organisation, ada)
                .header("X-Employee", grace))
        .andExpect(status().isForbidden());
  }
}
