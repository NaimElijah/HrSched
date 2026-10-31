package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
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
 * Specification that an enum field of a request body accepts only a constant's name (issue #326).
 *
 * <p>Jackson, as Boot configures it, bound a number, or a string holding one, to the constant at
 * that index: {@code {"role":2}} created an Admin though no request named ADMIN, and reordering an
 * enum would have silently changed what every index meant. So such a value is a malformed body: a
 * 400, whose detail does not repeat the field (ADR-024, ADR-056), and nothing is stored.
 *
 * <p>One pair of tests per enum field bound from a body: an account's role when it is created and
 * when its membership changes, and the strength of a stated preference. Each refusal runs twice,
 * the index written as a JSON number and as a JSON string, and each field has a control sending the
 * constant's name instead, which is accepted, so the refusal is the index's doing and not some
 * other fault in the body. The index sent is always that of the constant the control names, so a
 * binding that accepted it would have stored exactly what the control stores.
 *
 * <p>A query parameter is bound by Spring's conversion rather than by Jackson, and is pinned too:
 * the strength naming a preference to withdraw, sent as an index, is refused as an invalid request,
 * whose detail names the parameter and not the value (ADR-024), and nothing is withdrawn.
 *
 * <p>Every name, password and span here is invented for the test.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class EnumFieldHttpTest {

  /** Invented, and above the configured minimum, so a password never refuses a control. */
  private static final String A_PASSWORD = "an invented temporary password";

  /** Invented: the start of the span a preference is stated for. */
  private static final String PREFERENCE_START = "2026-10-06T08:00:00Z";

  /** Invented: the end of that span. */
  private static final String PREFERENCE_END = "2026-10-06T16:00:00Z";

  /** What every context's handler says of a body it cannot read, whatever was wrong with it. */
  private static final String MALFORMED = "Malformed request";

  /**
   * The one sentence every malformed body is answered with, whatever was wrong with it, so it has
   * to be true of each and repeat nothing sent.
   */
  private static final String MALFORMED_DETAIL =
      "The request body is not valid JSON, or it lacks a member this request requires, has one"
          + " it does not take, or has one whose value is of the wrong form.";

  /** The index the role refusals send, written as a JSON number: ADMIN's. */
  private static final String ADMIN_INDEX = Integer.toString(Role.ADMIN.ordinal());

  /** The index the preference refusals send, written as a JSON number: PREFERS_NOT's. */
  private static final String PREFERS_NOT_INDEX =
      Integer.toString(PreferenceStrength.PREFERS_NOT.ordinal());

  @Autowired private MockMvc mockMvc;

  // --- an account's role, when it is created ---------------------------------------------------

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void GivenARoleSentAsAnIndex_WhenCreatingAnAccount_ThenItIsMalformedAndNoAccountIsCreated(
      boolean quoted) throws Exception {
    String organisation = anOrganisation();
    String signInName = anInventedSignInName();

    String answer =
        refusedAsMalformed(
            asAdmin(post("/api/accounts"), organisation)
                .content(creating(signInName, written(ADMIN_INDEX, quoted))),
            "role");

    assertThat(answer).doesNotContain(signInName).doesNotContain(A_PASSWORD);
    accountsOf(organisation)
        .andExpect(jsonPath("$[?(@.signInName=='%s')]".formatted(signInName)).isEmpty());
  }

  @Test
  void GivenARoleSentByName_WhenCreatingTheSameAccount_ThenItHoldsThatRole() throws Exception {
    String organisation = anOrganisation();
    String signInName = anInventedSignInName();

    mockMvc
        .perform(
            asAdmin(post("/api/accounts"), organisation)
                .content(creating(signInName, named(Role.ADMIN))))
        .andExpect(status().isCreated());

    accountsOf(organisation)
        .andExpect(
            jsonPath("$[?(@.signInName=='%s')].role".formatted(signInName))
                .value(Role.ADMIN.name()));
  }

  // --- an account's role, when its membership changes -----------------------------------------

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void GivenARoleSentAsAnIndex_WhenChangingAMembership_ThenItIsMalformedAndTheRoleStays(
      boolean quoted) throws Exception {
    String organisation = anOrganisation();
    String signInName = anInventedSignInName();
    String account = aScheduler(organisation, signInName);

    refusedAsMalformed(
        asAdmin(put("/api/accounts/%s/membership".formatted(account)), organisation)
            .content(membership(written(ADMIN_INDEX, quoted))),
        "role");

    accountsOf(organisation)
        .andExpect(
            jsonPath("$[?(@.signInName=='%s')].role".formatted(signInName))
                .value(Role.SCHEDULER.name()));
  }

  @Test
  void GivenARoleSentByName_WhenChangingTheSameMembership_ThenTheAccountHoldsIt() throws Exception {
    String organisation = anOrganisation();
    String signInName = anInventedSignInName();
    String account = aScheduler(organisation, signInName);

    mockMvc
        .perform(
            asAdmin(put("/api/accounts/%s/membership".formatted(account)), organisation)
                .content(membership(named(Role.ADMIN))))
        .andExpect(status().isNoContent());

    accountsOf(organisation)
        .andExpect(
            jsonPath("$[?(@.signInName=='%s')].role".formatted(signInName))
                .value(Role.ADMIN.name()));
  }

  // --- the strength of a stated preference ----------------------------------------------------

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void GivenAStrengthSentAsAnIndex_WhenStatingAPreference_ThenItIsMalformedAndNothingIsStated(
      boolean quoted) throws Exception {
    String organisation = anOrganisation();
    String employee = anEmployee(organisation);

    refusedAsMalformed(
        asAdmin(post("/api/employees/%s/preferences".formatted(employee)), organisation)
            .content(preference(written(PREFERS_NOT_INDEX, quoted))),
        "strength");

    preferencesOf(organisation, employee).andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void GivenAStrengthSentByName_WhenStatingTheSamePreference_ThenItIsStated() throws Exception {
    String organisation = anOrganisation();
    String employee = anEmployee(organisation);

    mockMvc
        .perform(
            asAdmin(post("/api/employees/%s/preferences".formatted(employee)), organisation)
                .content(preference(named(PreferenceStrength.PREFERS_NOT))))
        .andExpect(status().isNoContent());

    preferencesOf(organisation, employee)
        .andExpect(jsonPath("$[0].strength").value(PreferenceStrength.PREFERS_NOT.name()));
  }

  // --- the strength of a preference being withdrawn, a query parameter --------------------------

  @Test
  void GivenAStrengthSentAsAnIndex_WhenWithdrawingAPreference_ThenItIsInvalidAndNothingIsWithdrawn()
      throws Exception {
    String organisation = anOrganisation();
    String employee = anEmployee(organisation);
    statePrefersNot(organisation, employee);

    mockMvc
        .perform(withdrawing(organisation, employee, PREFERS_NOT_INDEX))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid request"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "strength could not be read as " + PreferenceStrength.class.getSimpleName()));

    preferencesOf(organisation, employee)
        .andExpect(jsonPath("$[0].strength").value(PreferenceStrength.PREFERS_NOT.name()));
  }

  @Test
  void GivenAStrengthSentByName_WhenWithdrawingTheSamePreference_ThenItIsWithdrawn()
      throws Exception {
    String organisation = anOrganisation();
    String employee = anEmployee(organisation);
    statePrefersNot(organisation, employee);

    mockMvc
        .perform(withdrawing(organisation, employee, PreferenceStrength.PREFERS_NOT.name()))
        .andExpect(status().isNoContent());

    preferencesOf(organisation, employee).andExpect(jsonPath("$").isEmpty());
  }

  // --- what every field shares ----------------------------------------------------------------

  /**
   * @param member the field the refused value was sent in, which the answer must not name: a
   *     reference to it is how Jackson's own message would have repeated what was sent
   * @return the answer, for the caller to check that nothing else it sent comes back either
   */
  private String refusedAsMalformed(MockHttpServletRequestBuilder request, String member)
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
    assertThat(answer).doesNotContain("\"%s\"".formatted(member));
    return answer;
  }

  /** An index as a JSON value: a number, or the same digits as a string. */
  private static String written(String index, boolean quoted) {
    return quoted ? "\"%s\"".formatted(index) : index;
  }

  private static String named(Enum<?> constant) {
    return "\"%s\"".formatted(constant.name());
  }

  private static String creating(String signInName, String role) {
    return "{\"signInName\":\"%s\",\"temporaryPassword\":\"%s\",\"role\":%s}"
        .formatted(signInName, A_PASSWORD, role);
  }

  private static String membership(String role) {
    return "{\"role\":%s}".formatted(role);
  }

  private static String preference(String strength) {
    return "{\"start\":\"%s\",\"end\":\"%s\",\"strength\":%s}"
        .formatted(PREFERENCE_START, PREFERENCE_END, strength);
  }

  private void statePrefersNot(String organisation, String employee) throws Exception {
    mockMvc
        .perform(
            asAdmin(post("/api/employees/%s/preferences".formatted(employee)), organisation)
                .content(preference(named(PreferenceStrength.PREFERS_NOT))))
        .andExpect(status().isNoContent());
  }

  /** The withdrawal of the preference {@link #statePrefersNot} states, its strength as given. */
  private MockHttpServletRequestBuilder withdrawing(
      String organisation, String employee, String strength) throws Exception {
    return asAdmin(
        delete("/api/employees/%s/preferences".formatted(employee))
            .param("start", PREFERENCE_START)
            .param("end", PREFERENCE_END)
            .param("strength", strength),
        organisation);
  }

  private static String anInventedSignInName() {
    return "colleague-%s@example.com".formatted(UUID.randomUUID());
  }

  /**
   * @return the identifier of a Scheduler account just created in that organisation
   */
  private String aScheduler(String organisation, String signInName) throws Exception {
    String location =
        mockMvc
            .perform(
                asAdmin(post("/api/accounts"), organisation)
                    .content(creating(signInName, named(Role.SCHEDULER))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader("Location");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  private String anEmployee(String organisation) throws Exception {
    String body =
        mockMvc
            .perform(
                asAdmin(post("/api/employees"), organisation)
                    .content("{\"name\":\"Ada Lovelace\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private ResultActions accountsOf(String organisation) throws Exception {
    return mockMvc.perform(asAdmin(get("/api/accounts"), organisation)).andExpect(status().isOk());
  }

  private ResultActions preferencesOf(String organisation, String employee) throws Exception {
    return mockMvc
        .perform(asAdmin(get("/api/employees/%s/preferences".formatted(employee)), organisation))
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
    return TestTenants.as(mockMvc, request, organisation, Role.ADMIN.name());
  }
}
