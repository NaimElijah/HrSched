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
import org.junit.jupiter.params.provider.CsvSource;
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
 * Specification that a number written with a fraction part is refused where a whole number is
 * bound, not truncated (issue #237).
 *
 * <p>Jackson, as Boot configures it, accepted a floating-point number for an {@code int}, {@code
 * Integer} or {@code long} and dropped the fraction: a weight of 2.5 was stored as 2, a number
 * nobody sent, and a headcount of 0.5 became 0 and was then refused for the wrong reason. R40
 * requires a whole number, and the headcount, the thresholds and the contracted minutes are bound
 * as whole numbers by their request types. So such a number is a malformed body: a 400, whose
 * detail does not repeat the value (ADR-024), and nothing is stored. A whole number written as a
 * float, {@value #INVENTED_WHOLE_FLOAT}, is refused the same way, because the refusal reads the
 * token, not its value.
 *
 * <p>One family of whole-number fields per pair of tests: the shift's headcount, the rules'
 * thresholds, the objectives' weights and an employee's contracted minutes. Each family has a
 * control sending the same body with {@value #INVENTED_WHOLE} instead, which is accepted, so the
 * refusal is the number's doing and not some other fault in the body.
 *
 * <p>Every number and name here is invented for the test, and none is a recommendation or any
 * statute's.
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

  /** Invented: the same whole number as {@link #INVENTED_WHOLE}, written as a float. */
  private static final String INVENTED_WHOLE_FLOAT = "7.0";

  /** Invented: a whole number every field here accepts, sent by the controls. */
  private static final String INVENTED_WHOLE = "7";

  /** Invented: the start of the span the shift covers, read back to show what was stored. */
  private static final String SHIFT_START = "2026-10-06T08:00:00Z";

  /** Invented: the end of that span. */
  private static final String SHIFT_END = "2026-10-06T16:00:00Z";

  /**
   * Invented: the contracted week an employee holds before the refused change, a term of an
   * individual agreement and never a statute's.
   */
  private static final int INVENTED_CONTRACTED_MINUTES = 2101;

  /** What every context's handler says of a body it cannot read, whatever was wrong with it. */
  private static final String MALFORMED = "Malformed request";

  /** The version every organisation's rules start at, before anybody revises them. */
  private static final int FIRST_CONSTRAINT_VERSION = 1;

  @Autowired private MockMvc mockMvc;

  // --- a shift's headcount --------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {INVENTED_FRACTION, INVENTED_WHOLE_FLOAT})
  void GivenAHeadcountWithAFractionPart_WhenDefiningAShift_ThenItIsMalformedAndNoShiftIsStored(
      String headcount) throws Exception {
    String organisation = anOrganisation();

    refusedAsMalformed(
        as(post("/api/shifts"), organisation, "SCHEDULER").content(shift(headcount)), headcount);

    shiftsOf(organisation).andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void GivenAWholeHeadcount_WhenDefiningTheSameShift_ThenItIsStored() throws Exception {
    String organisation = anOrganisation();

    mockMvc
        .perform(as(post("/api/shifts"), organisation, "SCHEDULER").content(shift(INVENTED_WHOLE)))
        .andExpect(status().isCreated());

    shiftsOf(organisation)
        .andExpect(jsonPath("$[0].requiredHeadcount").value(Integer.parseInt(INVENTED_WHOLE)));
  }

  // --- the rules' thresholds ------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
    "minimumRestMinutes, " + INVENTED_FRACTION,
    "maximumDailyMinutes, " + INVENTED_FRACTION,
    "maximumWeeklyMinutes, " + INVENTED_FRACTION,
    "maximumConsecutiveDays, " + INVENTED_FRACTION,
    "minimumRestMinutes, " + INVENTED_WHOLE_FLOAT,
    "maximumDailyMinutes, " + INVENTED_WHOLE_FLOAT,
    "maximumWeeklyMinutes, " + INVENTED_WHOLE_FLOAT,
    "maximumConsecutiveDays, " + INVENTED_WHOLE_FLOAT
  })
  void GivenAThresholdWithAFractionPart_WhenRevisingTheRules_ThenItIsMalformedAndNoVersionIsStored(
      String threshold, String number) throws Exception {
    String organisation = anOrganisation();

    refusedAsMalformed(
        as(put("/api/constraints"), organisation, "ADMIN").content(single(threshold, number)),
        number);

    rulesOf(organisation)
        .andExpect(jsonPath("$.version").value(FIRST_CONSTRAINT_VERSION))
        .andExpect(jsonPath("$." + threshold).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "minimumRestMinutes",
        "maximumDailyMinutes",
        "maximumWeeklyMinutes",
        "maximumConsecutiveDays"
      })
  void GivenAWholeThreshold_WhenRevisingTheRulesTheSameWay_ThenANewVersionHoldsIt(String threshold)
      throws Exception {
    String organisation = anOrganisation();

    mockMvc
        .perform(
            as(put("/api/constraints"), organisation, "ADMIN")
                .content(single(threshold, INVENTED_WHOLE)))
        .andExpect(status().isOk());

    rulesOf(organisation)
        .andExpect(jsonPath("$.version").value(FIRST_CONSTRAINT_VERSION + 1))
        .andExpect(jsonPath("$." + threshold).value(Integer.parseInt(INVENTED_WHOLE)));
  }

  // --- the objectives' weights ----------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
    "fairness, " + INVENTED_FRACTION,
    "preferences, " + INVENTED_FRACTION,
    "disruption, " + INVENTED_FRACTION,
    "fairness, " + INVENTED_WHOLE_FLOAT,
    "preferences, " + INVENTED_WHOLE_FLOAT,
    "disruption, " + INVENTED_WHOLE_FLOAT
  })
  void
      GivenAWeightWithAFractionPart_WhenWeightingTheObjectives_ThenItIsMalformedAndNothingIsWeighed(
          String objective, String number) throws Exception {
    String organisation = anOrganisation();

    refusedAsMalformed(
        as(put("/api/objectives"), organisation, "ADMIN").content(single(objective, number)),
        number);

    weightsOf(organisation).andExpect(jsonPath("$." + objective).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"fairness", "preferences", "disruption"})
  void GivenAWholeWeight_WhenWeightingTheObjectivesTheSameWay_ThenItIsWeighed(String objective)
      throws Exception {
    String organisation = anOrganisation();

    mockMvc
        .perform(
            as(put("/api/objectives"), organisation, "ADMIN")
                .content(single(objective, INVENTED_WHOLE)))
        .andExpect(status().isOk());

    weightsOf(organisation)
        .andExpect(jsonPath("$." + objective).value(Integer.parseInt(INVENTED_WHOLE)));
  }

  // --- an employee's contracted minutes -------------------------------------------------------

  /**
   * The one whole-number field bound as a primitive {@code long}, read by a different deserializer
   * from the others and refused by the workforce context's own handler.
   */
  @ParameterizedTest
  @ValueSource(strings = {INVENTED_FRACTION, INVENTED_WHOLE_FLOAT})
  void
      GivenContractedMinutesWithAFractionPart_WhenSettingTheContract_ThenItIsMalformedAndTheOldOneStays(
          String minutes) throws Exception {
    String organisation = anOrganisation();
    String employee = anEmployeeContractedFor(organisation, INVENTED_CONTRACTED_MINUTES);

    refusedAsMalformed(
        as(put("/api/employees/" + employee + "/contract"), organisation, "ADMIN")
            .content(contract(minutes)),
        minutes);

    employeeOf(organisation, employee)
        .andExpect(jsonPath("$.weeklyContractedMinutes").value(INVENTED_CONTRACTED_MINUTES));
  }

  @Test
  void GivenWholeContractedMinutes_WhenSettingTheContractTheSameWay_ThenItReplacesTheOldOne()
      throws Exception {
    String organisation = anOrganisation();
    String employee = anEmployeeContractedFor(organisation, INVENTED_CONTRACTED_MINUTES);

    mockMvc
        .perform(
            as(put("/api/employees/" + employee + "/contract"), organisation, "ADMIN")
                .content(contract(INVENTED_WHOLE)))
        .andExpect(status().isNoContent());

    employeeOf(organisation, employee)
        .andExpect(jsonPath("$.weeklyContractedMinutes").value(Integer.parseInt(INVENTED_WHOLE)));
  }

  // --- what every family shares ---------------------------------------------------------------

  private void refusedAsMalformed(MockHttpServletRequestBuilder request, String sent)
      throws Exception {
    ResultActions refused =
        mockMvc
            .perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value(MALFORMED));

    assertThat(refused.andReturn().getResponse().getContentAsString()).doesNotContain(sent);
  }

  private static String shift(String headcount) {
    return "{\"start\":\"%s\",\"end\":\"%s\",\"zone\":\"UTC\",\"requiredHeadcount\":%s}"
        .formatted(SHIFT_START, SHIFT_END, headcount);
  }

  private static String single(String field, String number) {
    return "{\"%s\":%s}".formatted(field, number);
  }

  private static String contract(String minutes) {
    return "{\"weeklyContractedMinutes\":%s}".formatted(minutes);
  }

  private ResultActions shiftsOf(String organisation) throws Exception {
    return mockMvc
        .perform(
            as(get("/api/shifts"), organisation, "SCHEDULER")
                .param("from", SHIFT_START)
                .param("to", SHIFT_END))
        .andExpect(status().isOk());
  }

  private ResultActions rulesOf(String organisation) throws Exception {
    return mockMvc
        .perform(as(get("/api/constraints"), organisation, "ADMIN"))
        .andExpect(status().isOk());
  }

  private ResultActions weightsOf(String organisation) throws Exception {
    return mockMvc
        .perform(as(get("/api/objectives"), organisation, "ADMIN"))
        .andExpect(status().isOk());
  }

  private ResultActions employeeOf(String organisation, String employee) throws Exception {
    return mockMvc
        .perform(as(get("/api/employees/" + employee), organisation, "ADMIN"))
        .andExpect(status().isOk());
  }

  private String anEmployeeContractedFor(String organisation, int minutes) throws Exception {
    String body =
        mockMvc
            .perform(
                as(post("/api/employees"), organisation, "ADMIN")
                    .content("{\"name\":\"Ada Lovelace\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String employee = JsonPath.read(body, "$.id");
    mockMvc
        .perform(
            as(put("/api/employees/" + employee + "/contract"), organisation, "ADMIN")
                .content(contract(Integer.toString(minutes))))
        .andExpect(status().isNoContent());
    return employee;
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
