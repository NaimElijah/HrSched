package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Specification that a form body sent ahead of authentication is left unread when the security
 * chain refuses the request (issue #250).
 *
 * <p>Boot's form-content filter runs far ahead of the chain, and it reads a form body sent with
 * PUT, PATCH or DELETE whole, with no limit like the JSON one, so whoever sent it could make the
 * server hold all of it before any rule ran. No route takes form content, so the filter is off.
 * This asserts the behaviour rather than the absence of a bean: MockMvc runs every filter Boot
 * registers, however it was registered, so a body-reading filter brought back by any route shows
 * here as a consumed body.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class RequestBodyReadingFiltersTest {

  /** Invented: a form body of one field. */
  private static final String A_FORM_BODY = "invented=value";

  @Autowired private MockMvc mockMvc;

  @Test
  void GivenAnAnonymousFormBody_WhenPutToARouteTheChainRefuses_ThenTheBodyIsLeftUnread()
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                put("/api/constraints")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content(A_FORM_BODY))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(result.getRequest().getInputStream().isFinished())
        .describedAs("the request body was read before the security chain refused the request")
        .isFalse();
  }
}
