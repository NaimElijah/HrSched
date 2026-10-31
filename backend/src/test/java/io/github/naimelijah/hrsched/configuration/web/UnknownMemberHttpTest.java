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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Specification that a request body naming a member its request type does not have is refused, not
 * read as if the member were absent.
 *
 * <p>Jackson, as Boot configures it, dropped an unknown member without a word. That is harmless
 * only until the member is a misspelling: rules revised with {@code maximumConsecutiveDay} were
 * stored with that rule switched off, and answered 200, because a working-time value left out means
 * the rule does not apply. The same held for an objective weight. So an unknown member makes the
 * body malformed: a 400, whose detail repeats nothing sent, the member's name included (ADR-024,
 * ADR-058), and nothing is stored.
 *
 * <p>The rules and the objectives each have a control sending the same body spelt right, which is
 * accepted, so the refusal is the misspelling's doing. A third refusal, on adding an employee,
 * shows the workforce context's handler answering the same way; the access context's is pinned in
 * {@code AccountHttpTest}.
 *
 * <p>Every number and name here is invented for the test, and none is a recommendation or any
 * statute's.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class UnknownMemberHttpTest {

  /** Invented: a rest period sent beside the misspelt member, which must not be stored either. */
  private static final int INVENTED_REST_MINUTES = 613;

  /** Invented: the run of days the misspelt member meant to cap. */
  private static final int INVENTED_CONSECUTIVE_DAYS = 3;

  /** Invented: a fairness weight sent beside the misspelt member. */
  private static final int INVENTED_FAIRNESS = 2;

  /** Invented: the preferences weight the misspelt member meant to set. */
  private static final int INVENTED_PREFERENCES = 5;

  /** The misspelling of {@code maximumConsecutiveDays} that used to switch that rule off. */
  private static final String MISSPELT_CONSECUTIVE_DAYS = "maximumConsecutiveDay";

  /** A misspelling of {@code preferences}. */
  private static final String MISSPELT_PREFERENCES = "preferenses";

  /** A member no request to add an employee has, and whose name the answer must not repeat. */
  private static final String NOT_AN_EMPLOYEE_MEMBER = "weeklyContractedHours";

  /** What every context's handler says of a body it cannot read, whatever was wrong with it. */
  private static final String MALFORMED = "Malformed request";

  /**
   * The one sentence every malformed body is answered with, whatever was wrong with it, so it has
   * to be true of each and repeat nothing sent.
   */
  private static final String MALFORMED_DETAIL =
      "The request body is not valid JSON, or it lacks a member this request requires, has one"
          + " it does not take, or has one whose value is of the wrong form.";

  /** The version every organisation's rules start at, before anybody revises them. */
  private static final int FIRST_CONSTRAINT_VERSION = 1;

  @Autowired private MockMvc mockMvc;

  // --- the rules ------------------------------------------------------------------------------

  @Test
  void GivenAMisspeltRule_WhenRevisingTheRules_ThenItIsMalformedAndNoVersionIsStored()
      throws Exception {
    String organisation = anOrganisation();

    refusedAsMalformed(
        asAdmin(put("/api/constraints"), organisation).content(rules(MISSPELT_CONSECUTIVE_DAYS)),
        MISSPELT_CONSECUTIVE_DAYS);

    rulesOf(organisation)
        .andExpect(jsonPath("$.version").value(FIRST_CONSTRAINT_VERSION))
        .andExpect(jsonPath("$.minimumRestMinutes").isEmpty());
  }

  @Test
  void GivenTheSameRuleSpeltRight_WhenRevisingTheRules_ThenANewVersionHoldsBoth() throws Exception {
    String organisation = anOrganisation();

    mockMvc
        .perform(
            asAdmin(put("/api/constraints"), organisation).content(rules("maximumConsecutiveDays")))
        .andExpect(status().isOk());

    rulesOf(organisation)
        .andExpect(jsonPath("$.version").value(FIRST_CONSTRAINT_VERSION + 1))
        .andExpect(jsonPath("$.minimumRestMinutes").value(INVENTED_REST_MINUTES))
        .andExpect(jsonPath("$.maximumConsecutiveDays").value(INVENTED_CONSECUTIVE_DAYS));
  }

  // --- the objectives -------------------------------------------------------------------------

  @Test
  void GivenAMisspeltObjective_WhenWeightingTheObjectives_ThenItIsMalformedAndNothingIsWeighed()
      throws Exception {
    String organisation = anOrganisation();

    refusedAsMalformed(
        asAdmin(put("/api/objectives"), organisation).content(weights(MISSPELT_PREFERENCES)),
        MISSPELT_PREFERENCES);

    weightsOf(organisation)
        .andExpect(jsonPath("$.fairness").isEmpty())
        .andExpect(jsonPath("$.preferences").isEmpty());
  }

  @Test
  void GivenTheSameObjectiveSpeltRight_WhenWeightingTheObjectives_ThenBothAreWeighed()
      throws Exception {
    String organisation = anOrganisation();

    mockMvc
        .perform(asAdmin(put("/api/objectives"), organisation).content(weights("preferences")))
        .andExpect(status().isOk());

    weightsOf(organisation)
        .andExpect(jsonPath("$.fairness").value(INVENTED_FAIRNESS))
        .andExpect(jsonPath("$.preferences").value(INVENTED_PREFERENCES));
  }

  // --- the workforce context ------------------------------------------------------------------

  @Test
  void GivenAMemberAnEmployeeDoesNotHave_WhenAddingOne_ThenItIsMalformedAndNobodyIsAdded()
      throws Exception {
    String organisation = anOrganisation();

    refusedAsMalformed(
        asAdmin(post("/api/employees"), organisation)
            .content(
                "{\"name\":\"Ada Lovelace\",\"%s\":%d}"
                    .formatted(NOT_AN_EMPLOYEE_MEMBER, INVENTED_CONSECUTIVE_DAYS)),
        NOT_AN_EMPLOYEE_MEMBER);

    mockMvc
        .perform(asAdmin(get("/api/employees"), organisation))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  // --- what every body shares -----------------------------------------------------------------

  private void refusedAsMalformed(MockHttpServletRequestBuilder request, String unknownMember)
      throws Exception {
    String answer =
        mockMvc
            .perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value(MALFORMED))
            .andExpect(jsonPath("$.detail").value(MALFORMED_DETAIL))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(answer).doesNotContain(unknownMember);
  }

  /** The rest period, and the consecutive-days cap under whichever name is given. */
  private static String rules(String consecutiveDaysMember) {
    return "{\"minimumRestMinutes\":%d,\"%s\":%d}"
        .formatted(INVENTED_REST_MINUTES, consecutiveDaysMember, INVENTED_CONSECUTIVE_DAYS);
  }

  /** The fairness weight, and the preferences weight under whichever name is given. */
  private static String weights(String preferencesMember) {
    return "{\"fairness\":%d,\"%s\":%d}"
        .formatted(INVENTED_FAIRNESS, preferencesMember, INVENTED_PREFERENCES);
  }

  private ResultActions rulesOf(String organisation) throws Exception {
    return mockMvc
        .perform(asAdmin(get("/api/constraints"), organisation))
        .andExpect(status().isOk());
  }

  private ResultActions weightsOf(String organisation) throws Exception {
    return mockMvc
        .perform(asAdmin(get("/api/objectives"), organisation))
        .andExpect(status().isOk());
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

  private MockHttpServletRequestBuilder asAdmin(
      MockHttpServletRequestBuilder request, String organisation) throws Exception {
    return TestTenants.as(mockMvc, request, organisation, "ADMIN");
  }
}
