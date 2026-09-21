package io.github.naimelijah.hrsched.configuration.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Puts Spring Security's filter chain in front of every request.
 *
 * <p>A request carrying {@code Authorization: Bearer …} is now authenticated: the token is verified
 * by the application's {@link JwtDecoder}, and then turned into a caller by asking the database who
 * that account and session belong to <em>now</em>. A token that fails either step is refused with a
 * 401, on every route, whether or not the route itself requires authentication — that is the bearer
 * filter's own behaviour, and it means a client must send no token rather than a stale one.
 *
 * <p><strong>Still transitional.</strong> The session endpoints and the organisation routes require
 * a token; every other route is permitted and still takes its acting identity from {@code X-User},
 * {@code X-Role} and {@code X-Organisation}, which any caller can write. That placeholder is what
 * ADR-011 exists to delete, and it goes one route family at a time: each controller moves onto the
 * access token, one rule is added here per move, and the last change makes every request that no
 * rule permits be refused. This chain is on its way there, not there.
 *
 * <p>The decoder, the converter and the two refusal handlers are all injected <em>by type</em>.
 * Configuration may not name an adapter class (the architecture test fails on it), and that is not
 * a technicality here: it is what keeps every piece of knowledge about tokens inside the {@code
 * access} context rather than leaking into the composition root.
 *
 * <p>The chain is stateless, because nothing relies on an {@code HttpSession}: a bearer token is
 * presented fresh on every call. CSRF, form login, HTTP Basic, logout and the request cache are all
 * switched off — none applies to a token-carried identity, and CSRF protection for the one
 * cookie-carried credential is handled where that cookie is read, by requiring a header no
 * cross-site request can send.
 *
 * <p>No generated password is ever logged, and this class need not exclude {@code
 * UserDetailsServiceAutoConfiguration} to guarantee it. That auto-configuration applies only when
 * {@code OpaqueTokenIntrospector} is absent from the classpath (read from the jar), and the
 * resource-server starter carries that class regardless. {@code SecurityConfigurationTest} asserts
 * the absence of a {@code UserDetailsService} bean directly, so a future dependency change that
 * reopens this is caught rather than assumed shut.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  /**
   * Reading one's own session: the first of the two acts an account with a pending change may do.
   */
  private static final String SESSION = "/api/auth/session";

  /** Changing one's own password: the other, and the one that clears the pending change. */
  private static final String PASSWORD = "/api/auth/password";

  /** The register of tenants: administering it is the operator's, and only the operator's. */
  private static final String ORGANISATIONS = "/api/organisations";

  /** One organisation by identity. Readable by any member; the guard decides whose. */
  private static final String ONE_ORGANISATION = "/api/organisations/*";

  /** Account administration, which is an Admin's and happens inside their own organisation. */
  private static final String ACCOUNTS = "/api/accounts/**";

  /** Administering a tenant's staff, which the operator may not do (ADR-030). */
  private static final String ADMIN = "ADMIN";

  /**
   * Spring Security's own convention drops the {@code ROLE_} prefix in these, and the token adapter
   * grants the prefixed form. The two have to agree, and an HTTP test asserting an operator reaches
   * an operator-only route is what proves they do.
   */
  private static final String OPERATOR = "OPERATOR";

  private static final String[] EVERY_ROLE = {"OPERATOR", "ADMIN", "SCHEDULER", "EMPLOYEE"};

  /**
   * @param http the builder Spring Security hands every {@code SecurityFilterChain} bean
   * @param decoder the application's only {@code JwtDecoder}, which verifies an access token
   * @param actors turns a verified token into the caller it names, as of now
   * @param unauthenticated writes the 401 body and challenge
   * @param forbidden writes the 403 body
   * @return the chain every request passes through
   * @throws Exception declared by {@link HttpSecurity#build()}
   */
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder decoder,
      Converter<Jwt, ? extends AbstractAuthenticationToken> actors,
      AuthenticationEntryPoint unauthenticated,
      AccessDeniedHandler forbidden)
      throws Exception {
    return http.sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .oauth2ResourceServer(
            resource ->
                resource
                    .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(actors))
                    .authenticationEntryPoint(unauthenticated)
                    .accessDeniedHandler(forbidden))
        .exceptionHandling(
            refusals ->
                refusals.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden))
        .authorizeHttpRequests(
            requests ->
                requests
                    // Reachable with a temporary password still pending: they are the two things
                    // somebody in that state has to be able to do.
                    .requestMatchers(HttpMethod.GET, SESSION)
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, PASSWORD)
                    .authenticated()
                    // Administering the register of tenants. A role rather than merely being
                    // authenticated, so an account whose temporary password is still pending -
                    // which holds no role at all - is refused here like everywhere else.
                    .requestMatchers(HttpMethod.POST, ORGANISATIONS)
                    .hasRole(OPERATOR)
                    .requestMatchers(HttpMethod.GET, ORGANISATIONS)
                    .hasRole(OPERATOR)
                    // Any member may ask for an organisation by identity; which ones they get back
                    // is the use case's decision, and a tenant naming another gets a 404.
                    .requestMatchers(HttpMethod.GET, ONE_ORGANISATION)
                    .hasAnyRole(EVERY_ROLE)
                    // Account administration, in the organisation the token names and no other.
                    // An Admin only: the operator holds no role at all, so this rule refuses it
                    // before the controller has to (ADR-030).
                    .requestMatchers(ACCOUNTS)
                    .hasRole(ADMIN)
                    .anyRequest()
                    .permitAll())
        .build();
  }
}
