package io.github.naimelijah.hrsched.configuration.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Specification that the refusals Spring MVC makes on its own carry a problem detail (issue #275).
 *
 * <p>A wrong method on a mapped path, an {@code Accept} a route cannot answer, a body type a route
 * does not take, and a path no controller maps are all refused before, or without, any of the
 * contexts' handlers deciding anything. Each used to answer with a status and no body, so the SPA,
 * which shows every refusal's {@code detail}, had nothing to show. Each is now a problem detail of
 * its own type, with a constant detail that repeats nothing sent (ADR-024, ADR-057).
 *
 * <p>Every request but the sign-in carries a tenant's token, because the security chain refuses an
 * anonymous caller before Spring MVC sees the request, and every path is inside a route family the
 * chain admits a tenant to. Two tests pin that order without a token: neither a wrong method nor an
 * unmapped path tells an anonymous caller anything about the routes behind the chain. Annotated
 * identically to the other HTTP tests so they all share one Spring context. The bodies sent are
 * invented.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class FrameworkRefusalHttpTest {

  private static final String PROBLEM = "urn:hrsched:problem:";

  /** Invented: a body in a type no route of this API takes. */
  private static final String PLAIN_TEXT_BODY = "invented plain text";

  /**
   * The routing handler's sentence for a body type a route does not read. The charset filter's 415
   * says a JSON body must be UTF-8, so this one shows which of the two answered.
   */
  private static final String UNSUPPORTED_BODY_TYPE =
      "This route does not take a body of that media type.";

  /** A charset name that is not one, so the declared type does not parse at all. */
  private static final String UNPARSABLE_CHARSET = "application/json;charset=no-such-charset";

  /** Invented: an {@code Accept} that names no media type, so it does not parse at all. */
  private static final String UNPARSABLE_ACCEPT = "invented-not-a-media-type";

  @Autowired private MockMvc mockMvc;

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

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request)
      throws Exception {
    return TestTenants.as(mockMvc, request, anOrganisation(), "ADMIN");
  }

  /** RFC 9110 section 15.5.6: a 405 carries {@code Allow}, naming the methods the route takes. */
  @Test
  void GivenAMappedPath_WhenAskedWithAMethodItDoesNotTake_ThenItIsAProblemNamingTheAllowedOnes()
      throws Exception {
    mockMvc
        .perform(asAdmin(delete("/api/employees")))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEM + "method-not-allowed"))
        .andExpect(jsonPath("$.title").value("Method not allowed"))
        .andExpect(jsonPath("$.detail").value(not(containsString("DELETE"))))
        .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
  }

  /**
   * The SPA asks for JSON; a client asking for anything else still gets the refusal, in the one
   * type a problem detail is written in.
   */
  @Test
  void GivenAnAcceptNoRouteAnswersIn_WhenReading_ThenItIsANotAcceptableProblem() throws Exception {
    mockMvc
        .perform(asAdmin(get("/api/qualifications")).accept(MediaType.TEXT_PLAIN))
        .andExpect(status().isNotAcceptable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEM + "not-acceptable"))
        .andExpect(jsonPath("$.title").value("Not acceptable"))
        .andExpect(jsonPath("$.detail").value(not(containsString(MediaType.TEXT_PLAIN_VALUE))));
  }

  /**
   * An {@code Accept} that is not a media type at all. Spring refuses it as one it cannot answer
   * in, and the refusal still has to be written in some type: the one a problem detail is always
   * written in, rather than none, which would leave the caller a status and no body.
   */
  @Test
  void GivenAnAcceptThatDoesNotParse_WhenReading_ThenItIsStillANotAcceptableProblem()
      throws Exception {
    mockMvc
        .perform(asAdmin(get("/api/qualifications")).header(HttpHeaders.ACCEPT, UNPARSABLE_ACCEPT))
        .andExpect(status().isNotAcceptable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEM + "not-acceptable"))
        .andExpect(jsonPath("$.detail").value(not(containsString(UNPARSABLE_ACCEPT))));
  }

  /**
   * A route that names no {@code consumes}, so Spring refuses the body while reading the argument
   * rather than while choosing the route; sign-in, which names one, is refused while choosing it
   * (see {@code AuthHttpTest}).
   */
  @Test
  void GivenABodyTypeTheRouteDoesNotTake_WhenPosted_ThenItIsAnUnsupportedMediaTypeProblem()
      throws Exception {
    mockMvc
        .perform(
            asAdmin(post("/api/qualifications"))
                .contentType(MediaType.TEXT_PLAIN)
                .content(PLAIN_TEXT_BODY))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEM + "unsupported-media-type"))
        .andExpect(jsonPath("$.title").value("Unsupported media type"))
        .andExpect(jsonPath("$.detail").value(not(containsString(MediaType.TEXT_PLAIN_VALUE))));
  }

  /** Sign-in takes no token, so this is a refusal any caller on the internet can provoke. */
  @Test
  void
      GivenAContentTypeWhoseCharsetDoesNotParse_WhenSigningIn_ThenItIsAnUnsupportedMediaTypeProblem()
          throws Exception {
    mockMvc
        .perform(
            post("/api/auth/sign-in")
                .header(HttpHeaders.CONTENT_TYPE, UNPARSABLE_CHARSET)
                .content("{}"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEM + "unsupported-media-type"))
        .andExpect(jsonPath("$.detail").value(UNSUPPORTED_BODY_TYPE));
  }

  /**
   * The chain decides before Spring MVC does, so without a token a method the route does not take
   * is refused as every anonymous request is: a 401, with no {@code Allow} naming what the route
   * does take.
   */
  @Test
  void GivenNoToken_WhenAskingATenantRouteWithAMethodItDoesNotTake_ThenItIsUnauthenticated()
      throws Exception {
    mockMvc
        .perform(delete("/api/employees"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value(PROBLEM + "unauthenticated"))
        .andExpect(header().doesNotExist(HttpHeaders.ALLOW));
  }

  /** As above: a path no controller maps is not told apart from one that exists. */
  @Test
  void GivenNoToken_WhenReadingAnUnmappedPathInATenantRouteFamily_ThenItIsUnauthenticated()
      throws Exception {
    mockMvc
        .perform(get("/api/employees/%s/no-such-thing".formatted(UUID.randomUUID())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value(PROBLEM + "unauthenticated"))
        .andExpect(header().doesNotExist(HttpHeaders.ALLOW));
  }

  /**
   * A path inside a family the chain admits a tenant to, which no controller maps. Answered as any
   * other absence is, with the detail every 404 gives.
   */
  @Test
  void GivenAnUnmappedPathInATenantRouteFamily_WhenRead_ThenItIsTheNotFoundProblem()
      throws Exception {
    mockMvc
        .perform(asAdmin(get("/api/employees/%s/no-such-thing".formatted(UUID.randomUUID()))))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(PROBLEM + "not-found"))
        .andExpect(jsonPath("$.title").value("Not found"))
        .andExpect(jsonPath("$.detail").value("No such resource."));
  }
}
