package io.github.naimelijah.hrsched.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.security.web.session.SessionManagementFilter;

/**
 * Specification of exactly which filters stand in front of every request, and in what order.
 *
 * <p>A filter can answer a request by itself, ahead of every authorisation rule, so a filter nobody
 * chose is a route nobody decided about. That is how RFC 9728's protected resource metadata filter
 * arrived: a Spring Security upgrade added it to the resource-server configurer, it served a path
 * no rule names, and every test stayed green until somebody went looking (issue #250, ADR-050).
 * {@link SecurityConfiguration} removes that one; this test is what notices the next.
 *
 * <p>So it fails on any change to the list, including a harmless one, on purpose: an upgrade that
 * adds, removes or reorders a filter must fail the build until somebody has read what the new
 * filter answers and updated this list as a decision.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityFilterChainTest {

  @Autowired private SecurityFilterChain chain;

  @Test
  void GivenTheApplicationsSecurityChain_WhenItsFiltersAreListed_ThenTheyAreExactlyTheChosenOnes() {
    List<Class<? extends Filter>> filters =
        chain.getFilters().stream().<Class<? extends Filter>>map(Filter::getClass).toList();

    assertThat(filters)
        .describedAs("the security chain's filters, in order")
        .containsExactly(
            DisableEncodeUrlFilter.class,
            WebAsyncManagerIntegrationFilter.class,
            SecurityContextHolderFilter.class,
            HeaderWriterFilter.class,
            BearerTokenAuthenticationFilter.class,
            SecurityContextHolderAwareRequestFilter.class,
            AnonymousAuthenticationFilter.class,
            SessionManagementFilter.class,
            ExceptionTranslationFilter.class,
            AuthorizationFilter.class);
  }
}
