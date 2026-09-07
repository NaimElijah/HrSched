package io.github.naimelijah.hrsched.notifications.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Drives the notification API over HTTP.
 *
 * <p>Reading only, because there is deliberately no endpoint that raises a notification - the case
 * of an inbox with something in it is exercised where something puts it there, in the swap tests.
 * What this covers is the shape of the API and its refusals: whose inbox is read comes from the
 * acting identity, so there is no request that even expresses "somebody else's".
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class NotificationHttpTest {

  @Autowired private MockMvc mockMvc;

  private String anOrganisation() throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/organisations")
                    .header("X-User", UUID.randomUUID().toString())
                    .header("X-Role", "OPERATOR")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Organisation %s\"}".formatted(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private static MockHttpServletRequestBuilder asThemself(
      MockHttpServletRequestBuilder request, String organisation, String employee) {
    return request
        .header("X-Organisation", organisation)
        .header("X-User", UUID.randomUUID().toString())
        .header("X-Role", "EMPLOYEE")
        .header("X-Employee", employee)
        .contentType(MediaType.APPLICATION_JSON);
  }

  @Test
  void GivenNothingHasHappenedToThem_WhenReadingTheirInbox_ThenItIsEmpty() throws Exception {
    mockMvc
        .perform(
            asThemself(get("/api/notifications"), anOrganisation(), UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void GivenNoPersonnelRecordIsClaimed_WhenReadingAnInbox_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/notifications")
                .header("X-Organisation", anOrganisation())
                .header("X-User", UUID.randomUUID().toString())
                .header("X-Role", "ADMIN"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenTheOperator_WhenReadingAnInbox_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/notifications")
                .header("X-Organisation", anOrganisation())
                .header("X-User", UUID.randomUUID().toString())
                .header("X-Role", "OPERATOR"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenNoSuchNotification_WhenAcknowledgingIt_ThenItIsNotFound() throws Exception {
    mockMvc
        .perform(
            asThemself(
                post("/api/notifications/" + UUID.randomUUID() + "/read"),
                anOrganisation(),
                UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenAnIdentifierThatIsNotAUuid_WhenAcknowledgingIt_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(
            asThemself(
                post("/api/notifications/not-a-uuid/read"),
                anOrganisation(),
                UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAnOrganisationNobodyOnboarded_WhenReadingAnInbox_ThenThereIsNothingThere()
      throws Exception {
    mockMvc
        .perform(
            asThemself(
                get("/api/notifications"),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
  }
}
