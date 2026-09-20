package io.github.naimelijah.hrsched.access.adapter.in.token;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A verified caller, as Spring Security holds one.
 *
 * <p>The principal is the kernel {@link Actor} itself, so a controller can take the identity the
 * domain understands straight off the request rather than reassembling one from claims. That is the
 * whole reason this class exists: without it, every controller would be turning a token back into
 * an actor, and each of them could do it slightly differently.
 *
 * <p>Authorities come from the account as it is <em>now</em>, never from the token. A role carried
 * in a token would keep working until the token expired, which is exactly what demoting somebody is
 * meant to stop.
 *
 * <p>An account with a password change pending gets {@value #PASSWORD_CHANGE_REQUIRED} and
 * <strong>no role at all</strong>. It is not a lesser role but the absence of one: until the
 * temporary somebody else chose has been replaced, the only things this account may do are read its
 * own session and change that password.
 */
public final class ActorAuthentication extends AbstractAuthenticationToken {

  /** Spring Security's own convention: {@code hasRole("X")} looks for {@code ROLE_X}. */
  private static final String ROLE_PREFIX = "ROLE_";

  /** The role name an operator answers to, since {@code Role} has no value for one. */
  private static final String OPERATOR_ROLE = "OPERATOR";

  /**
   * Held <em>instead</em> of a role while a temporary password is pending, so that every rule
   * written in terms of a role refuses this caller without having to know why.
   */
  public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

  private final Actor actor;
  private final Jwt token;

  /**
   * @param actor who the caller is, never {@code null}
   * @param token the verified token they presented, never {@code null}
   * @param passwordChangeRequired whether they must change their password before anything else
   */
  public ActorAuthentication(Actor actor, Jwt token, boolean passwordChangeRequired) {
    super(authoritiesFor(actor, passwordChangeRequired));
    this.actor = Objects.requireNonNull(actor, "actor must not be null");
    this.token = Objects.requireNonNull(token, "token must not be null");
    setAuthenticated(true);
  }

  @Override
  public Actor getPrincipal() {
    return actor;
  }

  @Override
  public Jwt getCredentials() {
    return token;
  }

  /** The account identifier, which is what a log line or an audit entry should carry. */
  @Override
  public String getName() {
    return actor.id().value().toString();
  }

  private static Collection<GrantedAuthority> authoritiesFor(
      Actor actor, boolean passwordChangeRequired) {
    if (passwordChangeRequired) {
      return List.of(new SimpleGrantedAuthority(PASSWORD_CHANGE_REQUIRED));
    }
    return switch (actor) {
      case TenantActor tenant ->
          List.of(new SimpleGrantedAuthority(ROLE_PREFIX + tenant.role().name()));
      case Operator operator -> List.of(new SimpleGrantedAuthority(ROLE_PREFIX + OPERATOR_ROLE));
    };
  }
}
