package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives the qualification API over HTTP.
 *
 * <p>Defining is an Admin act; reading is not gated by role at all, because a Scheduler building a
 * roster and an Employee reading their own record both need to know what the words mean. Only the
 * tenant boundary applies to the listing.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class QualificationHttpTest {

  @Autowired private MockMvc mockMvc;

  /** Returns the Location the 201 names, so a test can follow it. */
  private String define(String organisation, String name) throws Exception {
    return mockMvc
        .perform(
            as(mockMvc, post("/api/qualifications"), organisation, "ADMIN")
                .content("{\"name\":\"%s\"}".formatted(name)))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getHeader("Location");
  }

  @Test
  void GivenAnAdmin_WhenDefiningAQualification_ThenItIsCreatedAndListed() throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, post("/api/qualifications"), organisation, "ADMIN")
                .content("{\"name\":\"Forklift\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Forklift"))
        .andExpect(jsonPath("$.id").isNotEmpty());

    mockMvc
        .perform(as(mockMvc, get("/api/qualifications"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Forklift"));
  }

  @Test
  void GivenASchedulerRole_WhenDefiningAQualification_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/qualifications"), anOrganisation(mockMvc), "SCHEDULER")
                .content("{\"name\":\"Forklift\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployeeRole_WhenDefiningAQualification_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/qualifications"), anOrganisation(mockMvc), "EMPLOYEE")
                .content("{\"name\":\"Forklift\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployeeRole_WhenListingQualifications_ThenTheyAreReturned() throws Exception {
    String organisation = anOrganisation(mockMvc);
    define(organisation, "Forklift");

    mockMvc
        .perform(as(mockMvc, get("/api/qualifications"), organisation, "EMPLOYEE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void GivenQualificationsOfAnotherOrganisation_WhenListing_ThenOnlyOurOwnAreReturned()
      throws Exception {
    String ours = anOrganisation(mockMvc);
    define(ours, "Forklift");
    define(anOrganisation(mockMvc), "Their Qualification");

    mockMvc
        .perform(as(mockMvc, get("/api/qualifications"), ours, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Forklift"));
  }

  @Test
  void GivenSeveralQualifications_WhenListing_ThenTheyComeBackSortedByName() throws Exception {
    String organisation = anOrganisation(mockMvc);
    define(organisation, "Welding");
    define(organisation, "Forklift");

    mockMvc
        .perform(as(mockMvc, get("/api/qualifications"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Forklift"))
        .andExpect(jsonPath("$[1].name").value("Welding"));
  }

  /** Flipped by ADR-030. */
  @Test
  void GivenTheOperator_WhenListingAnOrganisationsQualifications_ThenItIsForbidden()
      throws Exception {
    String theirs = anOrganisation(mockMvc);
    define(theirs, "Forklift");

    mockMvc
        .perform(as(mockMvc, get("/api/qualifications"), theirs, "OPERATOR"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenABlankName_WhenDefiningAQualification_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/qualifications"), anOrganisation(mockMvc), "ADMIN")
                .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoName_WhenDefiningAQualification_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/qualifications"), anOrganisation(mockMvc), "ADMIN")
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenADefinedQualification_WhenFollowingItsLocation_ThenItIsFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String location = define(organisation, "Forklift");

    mockMvc
        .perform(as(mockMvc, get(location), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Forklift"))
        .andExpect(jsonPath("$.id").isNotEmpty());
  }

  @Test
  void GivenAnEmployeeRole_WhenReadingAQualification_ThenItIsReturned() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String location = define(organisation, "Forklift");

    mockMvc
        .perform(as(mockMvc, get(location), organisation, "EMPLOYEE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Forklift"));
  }

  @Test
  void GivenAQualificationOfAnotherOrganisation_WhenReadThroughOurs_ThenThereIsNothingThere()
      throws Exception {
    String theirs = define(anOrganisation(mockMvc), "Their Qualification");

    mockMvc
        .perform(as(mockMvc, get(theirs), anOrganisation(mockMvc), "ADMIN"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").value("No such resource."));
  }
}
