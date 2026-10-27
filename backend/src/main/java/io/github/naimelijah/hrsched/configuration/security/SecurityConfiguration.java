package io.github.naimelijah.hrsched.configuration.security;

import jakarta.servlet.DispatcherType;
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
 * <p><strong>The chain is closed: it ends in {@code denyAll}.</strong> Every route this application
 * serves is named by a rule above it, and anything else is refused — including a route somebody
 * adds later and forgets to decide about. That is the whole point of ending this way: the failure
 * mode of {@code permitAll} is silent, and a new endpoint open to the world looks exactly like one
 * that was meant to be (ADR-032).
 *
 * <p>Every route under {@code /api} requires a token except the three that are authentication
 * itself, and every controller takes the acting identity from the account that token names. The
 * four identity headers are gone; nothing reads them, and a request that still sends them is
 * answered as whoever its token says it is.
 *
 * <p>These rules are the <strong>coarse outer line</strong>, and the use case behind each route is
 * what actually decides. Stating the fine-grained answer twice would mean two places to keep in
 * step, and the one that is easy to forget is the one that fails open.
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

  /** Authentication itself: the three acts somebody with no token has to be able to perform. */
  private static final String[] AUTHENTICATING = {
    "/api/auth/sign-in", "/api/auth/refresh", "/api/auth/sign-out"
  };

  /**
   * Health, info and the rest of the operational surface, apart from the metrics.
   *
   * <p>Open, as ADR-017 decided: whatever is actually reachable here is decided by {@code
   * management.endpoints.web.exposure.include}, and an endpoint that is not exposed answers 404
   * rather than 403 — which tells a caller nothing, the same reasoning the tenant 404 rests on.
   */
  private static final String ACTUATOR = "/actuator/**";

  /**
   * The metrics, which are exposed but refused to every caller, with or without a token.
   *
   * <p>They tell anybody who reads them whether a sign-in name exists: {@code
   * hrsched.changes{change=ACCOUNT_LOCKED}} counts up only when a real, active account locks, so
   * failing to sign in as a name until the lockout threshold and then reading the counter answers
   * the question sign-in itself is careful never to answer. Nothing scrapes them yet (Q19, #165),
   * so refusing them costs nothing today. Whether and how they reopen to a scraper is #78's
   * decision. They stay in the exposure list meanwhile, so the endpoints exist and only this rule
   * stands between them and a caller.
   */
  private static final String[] METRICS = {
    "/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus"
  };

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
   * Everything a member of an organisation does, which is every context but this one.
   *
   * <p>One list rather than a rule per capability, because these rules are the coarse outer line
   * and the use case behind each route is what actually decides. Writing the fine-grained answer
   * twice would mean two places to keep in step, and the one that is easy to forget is the one that
   * fails open.
   */
  private static final String[] TENANT_ROUTES = {
    "/api/employees/**",
    "/api/qualifications/**",
    "/api/shifts/**",
    "/api/constraints/**",
    "/api/objectives/**",
    "/api/rosters/**",
    "/api/me/**",
    "/api/swaps/**",
    "/api/notifications/**"
  };

  /** Any member of an organisation. Deliberately excludes the operator, which belongs to none. */
  private static final String[] EVERY_TENANT_ROLE = {"ADMIN", "SCHEDULER", "EMPLOYEE"};

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
                    // The container's own dispatches rather than requests anybody made. Without
                    // this, denyAll below turns every 500 into a 403 and hides the real failure.
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC)
                    .permitAll()
                    // Authentication itself. Signing in has no token by definition; refreshing and
                    // signing out are carried by the refresh cookie, and are defended against
                    // cross-site use by requiring a header no cross-site request can send.
                    .requestMatchers(HttpMethod.POST, AUTHENTICATING)
                    .permitAll()
                    // Before the actuator rule, which would otherwise open them: the first rule
                    // that matches decides. See METRICS for why they are refused.
                    .requestMatchers(METRICS)
                    .denyAll()
                    .requestMatchers(ACTUATOR)
                    .permitAll()
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
                    // An Admin only: the operator never holds a tenant role, because it belongs to
                    // no organisation, so this rule refuses it before the controller has to
                    // (ADR-030).
                    .requestMatchers(ACCOUNTS)
                    .hasRole(ADMIN)
                    // Everything inside an organisation. A role rather than merely being
                    // authenticated, which refuses two callers at once: the operator, which never
                    // holds a tenant role because it belongs to no organisation (ADR-030), and an
                    // account whose temporary password is still pending, which holds no role until
                    // it has chosen its own (ADR-029).
                    .requestMatchers(TENANT_ROUTES)
                    .hasAnyRole(EVERY_TENANT_ROLE)
                    // Everything else is refused. A route nobody wrote a rule for is a route
                    // nobody decided was safe, and the failure mode of the alternative is silent:
                    // a new controller would be open to the world and no test would say so
                    // (ADR-032).
                    .anyRequest()
                    .denyAll())
        .build();
  }
}
