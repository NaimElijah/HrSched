package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Specification that a fraction sent where a whole number is required is refused, not truncated
 * (issue #237).
 *
 * <p>Jackson, as Boot configures it, accepted a floating-point number for an {@code int}, {@code
 * Integer} or {@code long} and dropped the fraction: a weight of 2.5 was stored as 2, a number
 * nobody sent, and a headcount of 0.5 became 0 and was then refused for the wrong reason. A whole
 * number is part of what R8, R25 and R40 require, so the fraction is a malformed body: a 400, whose
 * detail does not repeat the value (ADR-024), and nothing is stored.
 *
 * <p>One family of whole-number fields per test: the shift's headcount, the rules' thresholds, the
 * objectives' weights. Every number here is invented for the test, and none is a recommendation or
 * any statute's.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class WholeNumberFieldHttpTest {

  /**
   * Invented: a fraction whose whole part every field here would accept, so that truncating it is
   * accepted rather than refused for some other reason, and distinctive enough that its absence
   * from a response means something.
   */
  private static final String INVENTED_FRACTION = "7.25";

  /** Invented: the span the refused shift would have covered, read back to show it is not there. */
  private static final String SHIFT_START = "2026-10-06T08:00:00Z";

  private static final String SHIFT_END = "2026-10-06T16:00:00Z";

  /** What every context's handler says of a body it cannot read, whatever was wrong with it. */
  private static final String MALFORMED = "Malformed request";

  /** The version every organisation's rules start at, before anybody revises them. */
  private static final int FIRST_CONSTRAINT_VERSION = 1;

  @Autowired private MockMvc mockMvc;

  @Test
  void GivenAFractionalHeadcount_WhenDefiningAShift_ThenItIsMalformedAndNoShiftIsStored()
      throws Exception {
    String organisation = anOrganisation();

    refusedAsMalformed(
        as(post("/api/shifts"), organisation, "SCHEDULER")
            .content(
                "{\"start\":\"%s\",\"end\":\"%s\",\"zone\":\"UTC\",\"requiredHeadcount\":%s}"
                    .formatted(SHIFT_START, SHIFT_END, INVENTED_FRACTION)));

    mockMvc
        .perform(
            as(get("/api/shifts"), organisation, "SCHEDULER")
                .param("from", SHIFT_START)
                .param("to", SHIFT_END))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "minimumRestMinutes",
        "maximumDailyMinutes",
        "maximumWeeklyMinutes",
        "maximumConsecutiveDays"
      })
  void GivenAFractionalThreshold_WhenRevisingTheRules_ThenItIsMalformedAndNoVersionIsStored(
      String threshold) throws Exception {
    String organisation = anOrganisation();

    refusedAsMalformed(
        as(put("/api/constraints"), organisation, "ADMIN")
            .content("{\"%s\":%s}".formatted(threshold, INVENTED_FRACTION)));

    mockMvc
        .perform(as(get("/api/constraints"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(FIRST_CONSTRAINT_VERSION))
        .andExpect(jsonPath("$." + threshold).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"fairness", "preferences", "disruption"})
  void GivenAFractionalWeight_WhenWeightingTheObjectives_ThenItIsMalformedAndNothingIsWeighed(
      String objective) throws Exception {
    String organisation = anOrganisation();

    refusedAsMalformed(
        as(put("/api/objectives"), organisation, "ADMIN")
            .content("{\"%s\":%s}".formatted(objective, INVENTED_FRACTION)));

    mockMvc
        .perform(as(get("/api/objectives"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$." + objective).isEmpty());
  }

  private void refusedAsMalformed(MockHttpServletRequestBuilder request) throws Exception {
    ResultActions refused =
        mockMvc
            .perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value(MALFORMED));

    assertThat(refused.andReturn().getResponse().getContentAsString())
        .doesNotContain(INVENTED_FRACTION);
  }

  private String anOrganisation() throws Exception {
    String body =
        mockMvc
            .perform(
                TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                    .content(
                        TestTenants.onboarding("Organisation %s".formatted(UUID.randomUUID()))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private MockHttpServletRequestBuilder as(
      MockHttpServletRequestBuilder request, String organisation, String role) throws Exception {
    return TestTenants.as(mockMvc, request, organisation, role);
  }
}
