package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives the qualification API over HTTP.
 *
 * <p>Defining is an Admin act; reading is not gated by role at all, because a Scheduler building a
 * roster and an Employee reading their own record both need to know what the words mean. Only the
 * tenant boundary applies to the listing.
 *
 * <p>Annotated identically to the other HTTP tests so all three share one Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
class QualificationHttpTest {

  @Autowired private MockMvc mockMvc;

  private void define(String organisation, String name) throws Exception {
    mockMvc
        .perform(
            as(post("/api/qualifications"), organisation, "ADMIN")
                .content("{\"name\":\"%s\"}".formatted(name)))
        .andExpect(status().isCreated());
  }

  @Test
  void GivenAnAdmin_WhenDefiningAQualification_ThenItIsCreatedAndListed() throws Exception {
    String organisation = anOrganisation();

    mockMvc
        .perform(
            as(post("/api/qualifications"), organisation, "ADMIN")
                .content("{\"name\":\"Forklift\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Forklift"))
        .andExpect(jsonPath("$.id").isNotEmpty());

    mockMvc
        .perform(as(get("/api/qualifications"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Forklift"));
  }

  @Test
  void GivenASchedulerRole_WhenDefiningAQualification_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(post("/api/qualifications"), anOrganisation(), "SCHEDULER")
                .content("{\"name\":\"Forklift\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployeeRole_WhenDefiningAQualification_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(post("/api/qualifications"), anOrganisation(), "EMPLOYEE")
                .content("{\"name\":\"Forklift\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployeeRole_WhenListingQualifications_ThenTheyAreReturned() throws Exception {
    String organisation = anOrganisation();
    define(organisation, "Forklift");

    mockMvc
        .perform(as(get("/api/qualifications"), organisation, "EMPLOYEE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void GivenQualificationsOfAnotherOrganisation_WhenListing_ThenOnlyOurOwnAreReturned()
      throws Exception {
    String ours = anOrganisation();
    define(ours, "Forklift");
    define(anOrganisation(), "Their Qualification");

    mockMvc
        .perform(as(get("/api/qualifications"), ours, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Forklift"));
  }

  @Test
  void GivenSeveralQualifications_WhenListing_ThenTheyComeBackSortedByName() throws Exception {
    String organisation = anOrganisation();
    define(organisation, "Welding");
    define(organisation, "Forklift");

    mockMvc
        .perform(as(get("/api/qualifications"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Forklift"))
        .andExpect(jsonPath("$[1].name").value("Welding"));
  }

  @Test
  void GivenAnOperator_WhenListingAnotherOrganisation_ThenItIsPermitted() throws Exception {
    String theirs = anOrganisation();
    define(theirs, "Forklift");

    mockMvc
        .perform(as(get("/api/qualifications"), theirs, "OPERATOR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void GivenABlankName_WhenDefiningAQualification_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(post("/api/qualifications"), anOrganisation(), "ADMIN").content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoName_WhenDefiningAQualification_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(as(post("/api/qualifications"), anOrganisation(), "ADMIN").content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAnUnknownRole_WhenDefiningAQualification_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(post("/api/qualifications"), anOrganisation(), "SUPERVISOR")
                .content("{\"name\":\"Forklift\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenANonUuidOrganisation_WhenListingQualifications_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(as(get("/api/qualifications"), "not-a-uuid", "ADMIN"))
        .andExpect(status().isBadRequest());
  }
}
