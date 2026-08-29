package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives the whole hexagon over HTTP: web adapter, driving port, use case, driven port, and the
 * in-memory persistence adapter.
 *
 * <p>Deliberately not a mocked controller slice. A walking skeleton is worth having only if
 * something proves the wiring end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShiftHttpTest {

  private static final String VALID_SHIFT =
      """
      {"start":"2026-09-01T08:00:00Z","end":"2026-09-01T16:00:00Z"}""";

  private static final String INVERTED_SHIFT =
      """
      {"start":"2026-09-01T16:00:00Z","end":"2026-09-01T08:00:00Z"}""";

  @Autowired private MockMvc mockMvc;

  @Test
  void GivenAValidPeriod_WhenPostingAShift_ThenItIsCreatedAndCanBeReadBack() throws Exception {
    String location =
        mockMvc
            .perform(
                post("/api/shifts").contentType(MediaType.APPLICATION_JSON).content(VALID_SHIFT))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.start").value("2026-09-01T08:00:00Z"))
            .andReturn()
            .getResponse()
            .getHeader("Location");

    assertThat(location).isNotNull();

    mockMvc
        .perform(get(location))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.end").value("2026-09-01T16:00:00Z"));
  }

  @Test
  void GivenAnEndBeforeItsStart_WhenPostingAShift_ThenTheRequestIsRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/shifts").contentType(MediaType.APPLICATION_JSON).content(INVERTED_SHIFT))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid shift period"));
  }

  @Test
  void GivenABodyMissingItsFields_WhenPostingAShift_ThenTheRequestIsRejected() throws Exception {
    mockMvc
        .perform(post("/api/shifts").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAnUnknownIdentity_WhenGettingAShift_ThenItIsNotFound() throws Exception {
    mockMvc.perform(get("/api/shifts/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }
}
