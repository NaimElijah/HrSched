package io.github.naimelijah.hrsched.configuration.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * Specification of what the security chain's 403 tells the caller it refused.
 *
 * <p>{@code ProblemAccessDeniedHandler} says one of two sentences, and the difference is the point:
 * an account whose temporary password is still pending is told to change it, because a message
 * about its role would send it looking for an administrator instead of a password field (ADR-029).
 * Every other caller the chain refuses hears the same sentence about its role, whichever rule
 * refused it, titled and typed as every context's handler titles and types a 403 of that kind, so a
 * role refusal reads the same whether the chain or a use case made it. The pending change is a kind
 * of its own, with its own title and type, so a client can tell the two apart without reading the
 * sentence (ADR-057).
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context. Every name
 * here is invented for the test.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChainRefusalDetailHttpTest {

  private static final String CHANGE_YOUR_PASSWORD_FIRST = "Change your temporary password first.";

  private static final String YOUR_ROLE_DOES_NOT_PERMIT_THIS = "Your role does not permit this.";

  /** The title every 403 of this kind carries, whether the chain or a use case refused it. */
  private static final String NOT_PERMITTED = "Not permitted";

  private static final String PASSWORD_CHANGE_REQUIRED = "Password change required";

  @Autowired private MockMvc mockMvc;

  /**
   * An organisation's first Admin is given a temporary password at onboarding, so until they change
   * it they hold no role, and a tenant route refuses them in the chain.
   */
  @Test
  void
      GivenAFirstAdminWhosePasswordChangeIsPending_WhenReadingATenantRoute_ThenTheyAreToldToChangeIt()
          throws Exception {
    String token = aFirstAdminsTokenBeforeTheirPasswordChange();

    mockMvc
        .perform(TestTenants.with(get("/api/qualifications"), token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value(PASSWORD_CHANGE_REQUIRED))
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:password-change-required"))
        .andExpect(jsonPath("$.detail").value(CHANGE_YOUR_PASSWORD_FIRST));
  }

  /** Account administration is an Admin's, so the chain refuses the operator for its role. */
  @Test
  void GivenTheOperator_WhenAdministeringAccounts_ThenTheyAreToldTheirRoleDoesNotPermitIt()
      throws Exception {
    mockMvc
        .perform(TestTenants.with(get("/api/accounts"), TestTenants.operatorToken(mockMvc)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value(NOT_PERMITTED))
        .andExpect(jsonPath("$.type").value("urn:hrsched:problem:not-permitted"))
        .andExpect(jsonPath("$.detail").value(YOUR_ROLE_DOES_NOT_PERMIT_THIS));
  }

  private String aFirstAdminsTokenBeforeTheirPasswordChange() throws Exception {
    String firstAdmin = "pending-first-admin-%s@example.com".formatted(UUID.randomUUID());
    mockMvc
        .perform(
            TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                .content(
                    TestTenants.onboarding(
                        "Organisation %s".formatted(UUID.randomUUID()), firstAdmin)))
        .andExpect(status().isCreated());
    String signedIn =
        mockMvc
            .perform(
                post("/api/auth/sign-in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"signInName\":\"%s\",\"password\":\"%s\"}"
                            .formatted(firstAdmin, TestTenants.password())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(signedIn, "$.accessToken");
  }
}
