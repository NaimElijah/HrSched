package io.github.naimelijah.hrsched.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Specification that every backend response tells a browser to do nothing with it but read it
 * (issue #250).
 *
 * <p>The API answers with JSON and nothing else, so no response of its has any reason to load a
 * script, a style or an image, to be framed, or to send the URL it came from onward. A policy that
 * forbids all of it costs nothing and turns a response a browser is somehow tricked into rendering
 * into an inert page. It holds on every response, whoever asked and however it was answered: an
 * open route, a refusal from the security chain, and an answer to a signed-in caller.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class ResponseSecurityHeadersHttpTest {

  private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
  private static final String NOTHING_LOADS_AND_NOTHING_FRAMES =
      "default-src 'none'; frame-ancestors 'none'";

  private static final String REFERRER_POLICY = "Referrer-Policy";
  private static final String NO_REFERRER = "no-referrer";

  private static final String SESSION = "/api/auth/session";

  @Autowired private MockMvc mockMvc;

  @Test
  void GivenNoToken_WhenAskingForHealth_ThenTheAnswerCarriesTheContentSecurityPolicy()
      throws Exception {
    MockHttpServletResponse response = health();

    assertThat(response.getHeader(CONTENT_SECURITY_POLICY))
        .isEqualTo(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenNoToken_WhenAskingForHealth_ThenTheAnswerCarriesTheReferrerPolicy() throws Exception {
    MockHttpServletResponse response = health();

    assertThat(response.getHeader(REFERRER_POLICY)).isEqualTo(NO_REFERRER);
  }

  @Test
  void GivenNoToken_WhenReadingTheSession_ThenTheRefusalCarriesTheContentSecurityPolicy()
      throws Exception {
    MockHttpServletResponse response = sessionWithoutAToken();

    assertThat(response.getHeader(CONTENT_SECURITY_POLICY))
        .isEqualTo(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenNoToken_WhenReadingTheSession_ThenTheRefusalCarriesTheReferrerPolicy()
      throws Exception {
    MockHttpServletResponse response = sessionWithoutAToken();

    assertThat(response.getHeader(REFERRER_POLICY)).isEqualTo(NO_REFERRER);
  }

  @Test
  void GivenASignedInCaller_WhenReadingTheirSession_ThenTheAnswerCarriesTheContentSecurityPolicy()
      throws Exception {
    MockHttpServletResponse response = sessionAsTheOperator();

    assertThat(response.getHeader(CONTENT_SECURITY_POLICY))
        .isEqualTo(NOTHING_LOADS_AND_NOTHING_FRAMES);
  }

  @Test
  void GivenASignedInCaller_WhenReadingTheirSession_ThenTheAnswerCarriesTheReferrerPolicy()
      throws Exception {
    MockHttpServletResponse response = sessionAsTheOperator();

    assertThat(response.getHeader(REFERRER_POLICY)).isEqualTo(NO_REFERRER);
  }

  private MockHttpServletResponse health() throws Exception {
    MockHttpServletResponse response =
        mockMvc.perform(get("/actuator/health")).andReturn().getResponse();
    assertThat(response.getStatus()).describedAs("arranged: health answers").isEqualTo(200);
    return response;
  }

  private MockHttpServletResponse sessionWithoutAToken() throws Exception {
    MockHttpServletResponse response = mockMvc.perform(get(SESSION)).andReturn().getResponse();
    assertThat(response.getStatus()).describedAs("arranged: refused").isEqualTo(401);
    return response;
  }

  private MockHttpServletResponse sessionAsTheOperator() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(TestTenants.with(get(SESSION), TestTenants.operatorToken(mockMvc)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).describedAs("arranged: answered").isEqualTo(200);
    return response;
  }
}
