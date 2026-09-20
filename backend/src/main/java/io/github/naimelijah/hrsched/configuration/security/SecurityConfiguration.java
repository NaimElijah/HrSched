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
 * <p><strong>Still transitional.</strong> Only the two session endpoints require authentication;
 * every other route is permitted and still takes its acting identity from {@code X-User}, {@code
 * X-Role} and {@code X-Organisation}, which any caller can write. That placeholder is what ADR-011
 * exists to delete, and it goes one route family at a time: each controller moves onto the access
 * token, one rule is added here per move, and the last change makes every request that no rule
 * permits be refused. This chain is on its way there, not there.
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
                    .anyRequest()
                    .permitAll())
        .build();
  }
}
