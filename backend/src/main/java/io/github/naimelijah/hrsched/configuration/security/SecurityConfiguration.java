package io.github.naimelijah.hrsched.configuration.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Puts Spring Security's filter chain in front of every request, configured to change nothing about
 * who may do what.
 *
 * <p>Adding the resource-server starter to the classpath is enough, on its own, for Boot to lock
 * every request behind {@code anyRequest().authenticated()} and its own defaults for form login and
 * HTTP Basic. Declaring a {@link SecurityFilterChain} bean here makes Boot's own default chain back
 * off (it is conditional on no application-defined one existing), and the chain this declares
 * authenticates nobody and refuses nobody instead: {@code anyRequest().permitAll()}.
 *
 * <p>The acting identity still comes from {@code X-User}, {@code X-Role} and {@code
 * X-Organisation}, read by the header-based placeholder this chain does not touch and does not
 * protect. That placeholder is what ADR-011 exists to delete. Its replacement lands one route
 * family at a time: each controller moves onto an access token as its identity source, one rule
 * added to this chain per move, until every request that no rule permits is refused. This class is
 * that chain's starting point, not its finished shape - see ADR-011.
 *
 * <p>The chain is stateless, because no request here relies on an {@code HttpSession}: the
 * placeholder identity is read fresh from headers on every call. CSRF, form login, HTTP Basic,
 * logout and the request cache are all switched off because none of them apply to a header-carried
 * identity, and leaving CSRF on would refuse the state-changing requests the existing API already
 * accepts - the one behaviour this class must not change.
 *
 * <p>No generated password is ever logged, and this class need not exclude {@code
 * UserDetailsServiceAutoConfiguration} to guarantee it. That auto-configuration already backs off
 * on its own: it applies only when {@code OpaqueTokenIntrospector} is absent from the classpath
 * ({@code MissingAlternativeUserDetailsManagerOrUserPropertiesConfigured}, read from the jar), and
 * the resource-server starter this project depends on carries that class regardless of whether any
 * decoder or introspector bean is ever configured. {@code SecurityConfigurationTest} asserts the
 * absence of a {@code UserDetailsService} bean directly, so a future dependency change that reopens
 * this is caught rather than assumed shut.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  /**
   * @param http the builder Spring Security hands every {@code SecurityFilterChain} bean
   * @return a chain that authenticates no request and permits every one
   * @throws Exception declared by {@link HttpSecurity#build()}; nothing configured here throws
   */
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .build();
  }
}
