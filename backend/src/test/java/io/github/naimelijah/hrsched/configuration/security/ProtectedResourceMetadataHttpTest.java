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
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Specification that the resource server publishes no OAuth 2.0 protected-resource metadata (issue
 * #250).
 *
 * <p>Spring Security 7.1's resource-server configurer adds a filter answering {@code GET
 * /.well-known/oauth-protected-resource} by itself, ahead of the authorisation rules, so the route
 * was served although no rule in {@link SecurityConfiguration} names it — which is exactly what
 * ADR-032's {@code denyAll} exists to prevent. What it served was also false: it claimed
 * certificate-bound access tokens, which this service does not issue, and it built the resource
 * identifier from whatever {@code Host} header the request carried. No client of this API discovers
 * it through that document, so the route falls to {@code denyAll} like any other unnamed path, and
 * a 401 challenge stops pointing at it.
 *
 * <p>A request with no token is answered 401 by {@code denyAll} and {@code authenticated()} alike,
 * so the operator's token, refused with a 403, is what tells the two apart.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProtectedResourceMetadataHttpTest {

  private static final String METADATA = "/.well-known/oauth-protected-resource";

  /** RFC 9728's challenge parameter pointing a client at the metadata document. */
  private static final String RESOURCE_METADATA_PARAMETER = "resource_metadata";

  @Autowired private MockMvc mockMvc;

  @Test
  void GivenNoToken_WhenAskingForProtectedResourceMetadata_ThenItIsUnauthorised() throws Exception {
    MockHttpServletResponse response = mockMvc.perform(get(METADATA)).andReturn().getResponse();

    assertThat(response.getStatus())
        .describedAs("body answered: %s", response.getContentAsString())
        .isEqualTo(401);
  }

  /** RFC 9728 also places the document of a resource with a path beneath the well-known name. */
  @Test
  void GivenNoToken_WhenAskingForTheMetadataOfAResourcePath_ThenItIsUnauthorised()
      throws Exception {
    MockHttpServletResponse response =
        mockMvc.perform(get(METADATA + "/api/rosters")).andReturn().getResponse();

    assertThat(response.getStatus())
        .describedAs("body answered: %s", response.getContentAsString())
        .isEqualTo(401);
  }

  @Test
  void GivenTheOperatorsToken_WhenAskingForProtectedResourceMetadata_ThenItIsForbidden()
      throws Exception {
    String token = TestTenants.operatorToken(mockMvc);

    MockHttpServletResponse response =
        mockMvc.perform(TestTenants.with(get(METADATA), token)).andReturn().getResponse();

    assertThat(response.getStatus())
        .describedAs("body answered: %s", response.getContentAsString())
        .isEqualTo(403);
  }

  @Test
  void GivenTheOperatorsToken_WhenAskingForTheMetadataOfAResourcePath_ThenItIsForbidden()
      throws Exception {
    String token = TestTenants.operatorToken(mockMvc);

    MockHttpServletResponse response =
        mockMvc
            .perform(TestTenants.with(get(METADATA + "/api/rosters"), token))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus())
        .describedAs("body answered: %s", response.getContentAsString())
        .isEqualTo(403);
  }

  @Test
  void GivenNoToken_WhenARouteRefusesIt_ThenTheChallengePointsAtNoMetadata() throws Exception {
    MockHttpServletResponse response =
        mockMvc.perform(get("/api/auth/session")).andReturn().getResponse();

    assertThat(response.getStatus()).describedAs("arranged: refused").isEqualTo(401);
    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        .isNotNull()
        .doesNotContain(RESOURCE_METADATA_PARAMETER);
  }

  @Test
  void GivenAnInvalidToken_WhenARouteRefusesIt_ThenTheChallengePointsAtNoMetadata()
      throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                get("/api/auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).describedAs("arranged: refused").isEqualTo(401);
    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        .isNotNull()
        .doesNotContain(RESOURCE_METADATA_PARAMETER);
  }
}
