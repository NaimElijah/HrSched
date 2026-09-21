package io.github.naimelijah.hrsched.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves {@link SecurityConfiguration} does what it claims: no generated user is created, and the
 * existing API still answers exactly as it did before Spring Security joined the classpath.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigurationTest {

  @Autowired private ApplicationContext context;
  @Autowired private MockMvc mockMvc;

  /**
   * {@code UserDetailsServiceAutoConfiguration} already backs off on its own here - the
   * resource-server starter carries {@code OpaqueTokenIntrospector} on the classpath regardless of
   * which beans this application defines, which is enough to disable it (see {@link
   * SecurityConfiguration}'s class Javadoc). This is a regression guard rather than a case this
   * task's production code drives from red to green: it protects against a future dependency change
   * quietly reopening the path to a generated, logged password.
   */
  @Test
  void GivenSpringSecurity_WhenTheApplicationStarts_ThenNoGeneratedUserExists() {
    assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
  }

  /**
   * A real state-changing request the existing API accepts (the same onboarding request {@code
   * OrganisationHttpTest} drives), sent with no CSRF token. Spring Security refuses an unsafe
   * request without one unless CSRF protection is switched off, so a 201 here is proof {@link
   * SecurityConfiguration} disabled it rather than merely sitting in front of the request.
   */
  @Test
  void GivenSpringSecurity_WhenAStateChangingRequestArrivesWithoutACsrfToken_ThenItIsNotRefused()
      throws Exception {
    mockMvc
        .perform(
            TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                .content("{\"name\":\"Acme Security Test %s\"}".formatted(UUID.randomUUID())))
        .andExpect(status().isCreated());
  }
}
