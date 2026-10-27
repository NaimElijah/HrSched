package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.as;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.asThemself;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A span that ends before it starts is refused 400, and the problem detail repeats neither instant.
 *
 * <p>A span says when somebody cannot work, or would rather not, and a detail travels wherever the
 * caller logs it (ADR-024). Each route that turns two instants from a request into a span is
 * checked: declaring and withdrawing unavailability, and stating and withdrawing a preference.
 *
 * <p>{@code EmployeeHttpTest} already pins that a reversed span is a 400 on the first two routes;
 * this adds what the detail may say, and the two preference routes.
 *
 * <p>EVERY INSTANT BELOW IS INVENTED. Each test reverses a span on its own day, and asserts that
 * day appears nowhere in the detail, which catches any rendering of either instant.
 *
 * <p>Annotated identically to {@code EmployeeHttpTest}, so it shares the suite's Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReversedSpanHttpTest {

  @Autowired private MockMvc mockMvc;

  private String addEmployee(String organisation) throws Exception {
    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                    .content("{\"name\":\"Ada\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private static void isRefusedRepeatingNeither(
      ResultActions response, String day, String start, String end) throws Exception {
    response
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").isNotEmpty())
        .andExpect(jsonPath("$.detail").value(not(containsString(start))))
        .andExpect(jsonPath("$.detail").value(not(containsString(end))))
        .andExpect(jsonPath("$.detail").value(not(containsString(day))));
  }

  @Test
  void GivenAnEndBeforeItsStart_WhenDeclaringOwnUnavailability_ThenRefusedRepeatingNeither()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);
    String day = "2026-12-11";
    String start = day + "T16:00:00Z";
    String end = day + "T08:00:00Z";

    isRefusedRepeatingNeither(
        mockMvc.perform(
            asThemself(
                    mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, ada)
                .content("{\"start\":\"%s\",\"end\":\"%s\"}".formatted(start, end))),
        day,
        start,
        end);
  }

  @Test
  void GivenAnEndBeforeItsStart_WhenStatingOwnPreference_ThenRefusedRepeatingNeither()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);
    String day = "2026-12-12";
    String start = day + "T16:00:00Z";
    String end = day + "T08:00:00Z";

    isRefusedRepeatingNeither(
        mockMvc.perform(
            asThemself(mockMvc, post("/api/employees/" + ada + "/preferences"), organisation, ada)
                .content(
                    "{\"start\":\"%s\",\"end\":\"%s\",\"strength\":\"PREFERS\"}"
                        .formatted(start, end))),
        day,
        start,
        end);
  }

  @Test
  void GivenAnEndBeforeItsStart_WhenWithdrawingOwnUnavailability_ThenRefusedRepeatingNeither()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);
    String day = "2026-12-13";
    String start = day + "T16:00:00Z";
    String end = day + "T08:00:00Z";

    isRefusedRepeatingNeither(
        mockMvc.perform(
            asThemself(
                mockMvc,
                delete("/api/employees/" + ada + "/unavailability")
                    .param("start", start)
                    .param("end", end),
                organisation,
                ada)),
        day,
        start,
        end);
  }

  @Test
  void GivenAnEndBeforeItsStart_WhenWithdrawingOwnPreference_ThenRefusedRepeatingNeither()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);
    String day = "2026-12-14";
    String start = day + "T16:00:00Z";
    String end = day + "T08:00:00Z";

    isRefusedRepeatingNeither(
        mockMvc.perform(
            asThemself(
                mockMvc,
                delete("/api/employees/" + ada + "/preferences")
                    .param("start", start)
                    .param("end", end)
                    .param("strength", "PREFERS"),
                organisation,
                ada)),
        day,
        start,
        end);
  }
}
