package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Weighting the objectives over HTTP (F35, F36, ADR-010): an Admin sets the weights, any member
 * reads them, and each organisation sees only its own.
 *
 * <p>Every weight here is invented. None is a recommendation; an organisation chooses its own.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class ObjectivesHttpTest {

  @Autowired private MockMvc mockMvc;

  /** Nothing weighted is answered as such, every weight null, rather than as a missing record. */
  @Test
  void GivenNothingWeighted_WhenReadingTheWeights_ThenEveryObjectiveIsUnweighted()
      throws Exception {
    mockMvc
        .perform(as(mockMvc, get("/api/objectives"), anOrganisation(mockMvc), "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fairness").isEmpty())
        .andExpect(jsonPath("$.preferences").isEmpty())
        .andExpect(jsonPath("$.disruption").isEmpty());
  }

  @Test
  void GivenAnAdmin_WhenWeightingTheObjectives_ThenTheWeightsAreEchoedAndReadBack()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, put("/api/objectives"), organisation, "ADMIN")
                .content("{\"fairness\":3,\"disruption\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fairness").value(3))
        .andExpect(jsonPath("$.preferences").isEmpty())
        .andExpect(jsonPath("$.disruption").value(1));

    mockMvc
        .perform(as(mockMvc, get("/api/objectives"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fairness").value(3))
        .andExpect(jsonPath("$.disruption").value(1));
  }

  /** F36: any member reads them, as any member reads the rules (F21). */
  @Test
  void GivenAnEmployee_WhenReadingTheWeights_ThenTheyAreShown() throws Exception {
    String organisation = anOrganisation(mockMvc);
    mockMvc
        .perform(
            as(mockMvc, put("/api/objectives"), organisation, "ADMIN")
                .content("{\"preferences\":2}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(mockMvc, get("/api/objectives"), organisation, "EMPLOYEE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.preferences").value(2));
  }

  @Test
  void GivenAScheduler_WhenWeightingTheObjectives_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, put("/api/objectives"), anOrganisation(mockMvc), "SCHEDULER")
                .content("{\"fairness\":3}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployee_WhenWeightingTheObjectives_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, put("/api/objectives"), anOrganisation(mockMvc), "EMPLOYEE")
                .content("{\"fairness\":3}"))
        .andExpect(status().isForbidden());
  }

  /** R40: zero would be a second way to say "not weighed", so it is refused, naming no value. */
  @Test
  void GivenAWeightOfZero_WhenWeighting_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, put("/api/objectives"), anOrganisation(mockMvc), "ADMIN")
                .content("{\"fairness\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "The fairness weight must be a whole number of at least one; leave it out"
                        + " to weigh nothing"));
  }

  @Test
  void GivenTwoOrganisations_WhenOneWeightsItsObjectives_ThenTheOtherIsUntouched()
      throws Exception {
    String first = anOrganisation(mockMvc);
    String second = anOrganisation(mockMvc);

    mockMvc
        .perform(as(mockMvc, put("/api/objectives"), first, "ADMIN").content("{\"fairness\":5}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(mockMvc, get("/api/objectives"), second, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fairness").isEmpty());
  }
}
