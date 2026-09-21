package io.github.naimelijah.hrsched.access.adapter.in.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.access.application.port.in.AuthenticatedActor;
import io.github.naimelijah.hrsched.access.application.port.in.AuthenticatedActorsUseCase;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * What a verified token turns into, and what it does not.
 *
 * <p>Two properties here are security ones. A caller with a pending password change gets that
 * authority and <strong>no role</strong>, so every rule written in terms of a role refuses them
 * without having to know why. And every way of failing raises the same refusal with the same
 * message, so a caller cannot learn from a 401 whether the account exists, is disabled, or was
 * signed out a moment ago.
 *
 * <p>Authorities are asserted against the account as the use case reports it now, never against
 * anything in the token: that is what makes a demotion bite on the next request.
 */
class ActorAuthenticationConverterTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final UserId ACCOUNT = UserId.newId();
  private static final SignInSessionId SESSION = SignInSessionId.newId();

  /** The one message every refusal carries, whichever of them it was. */
  private static final String ONE_REFUSAL = "The access token was not accepted";

  private final StubAuthenticatedActors actors = new StubAuthenticatedActors();
  private final ActorAuthenticationConverter converter = new ActorAuthenticationConverter(actors);

  @Test
  void GivenAnAdminsToken_WhenConverted_ThenTheCallerHoldsTheAdminRole() {
    actors.given(new TenantActor(ACCOUNT, ACME, Role.ADMIN), false);

    assertThat(authoritiesOf(converter.convert(aToken()))).containsExactly("ROLE_ADMIN");
  }

  @Test
  void GivenASchedulersToken_WhenConverted_ThenTheCallerHoldsTheSchedulerRole() {
    actors.given(new TenantActor(ACCOUNT, ACME, Role.SCHEDULER), false);

    assertThat(authoritiesOf(converter.convert(aToken()))).containsExactly("ROLE_SCHEDULER");
  }

  @Test
  void GivenAnEmployeesToken_WhenConverted_ThenTheCallerHoldsTheEmployeeRole() {
    actors.given(new TenantActor(ACCOUNT, ACME, Role.EMPLOYEE), false);

    assertThat(authoritiesOf(converter.convert(aToken()))).containsExactly("ROLE_EMPLOYEE");
  }

  @Test
  void GivenAnOperatorsToken_WhenConverted_ThenTheCallerHoldsTheOperatorRole() {
    actors.given(new Operator(ACCOUNT), false);

    assertThat(authoritiesOf(converter.convert(aToken()))).containsExactly("ROLE_OPERATOR");
  }

  /**
   * Not a lesser role but the absence of one. Until the temporary password somebody else chose has
   * been replaced, every rule about roles refuses this caller, and only the two endpoints written
   * in terms of being authenticated let them through.
   */
  @Test
  void GivenAPendingPasswordChange_WhenConverted_ThenTheCallerHoldsNoRoleAtAll() {
    actors.given(new TenantActor(ACCOUNT, ACME, Role.ADMIN), true);

    assertThat(authoritiesOf(converter.convert(aToken())))
        .containsExactly("PASSWORD_CHANGE_REQUIRED");
  }

  @Test
  void GivenATokenTheUseCaseDoesNotRecognise_WhenConverted_ThenItIsRefused() {
    assertThatThrownBy(() -> converter.convert(aToken()))
        .isInstanceOf(InvalidBearerTokenException.class);
  }

  @Test
  void GivenATokenWhoseSubjectIsNotAnIdentifier_WhenConverted_ThenItIsRefusedTheSameWay() {
    actors.given(new Operator(ACCOUNT), false);

    assertThatThrownBy(() -> converter.convert(tokenWith("not-a-uuid", SESSION.value().toString())))
        .isInstanceOf(InvalidBearerTokenException.class)
        .hasMessage(ONE_REFUSAL);
  }

  @Test
  void GivenATokenWhoseSessionIsNotAnIdentifier_WhenConverted_ThenItIsRefusedTheSameWay() {
    actors.given(new Operator(ACCOUNT), false);

    assertThatThrownBy(() -> converter.convert(tokenWith(ACCOUNT.value().toString(), "not-a-uuid")))
        .isInstanceOf(InvalidBearerTokenException.class)
        .hasMessage(ONE_REFUSAL);
  }

  @Test
  void GivenAConvertedToken_WhenAsked_ThenItsPrincipalIsTheActorAndItsNameTheAccount() {
    Actor actor = new TenantActor(ACCOUNT, ACME, Role.ADMIN);
    actors.given(actor, false);

    AbstractAuthenticationToken authenticated = converter.convert(aToken());

    assertThat(authenticated.getPrincipal()).isEqualTo(actor);
    assertThat(authenticated.getName()).isEqualTo(ACCOUNT.value().toString());
    assertThat(authenticated.isAuthenticated()).isTrue();
  }

  private static Jwt aToken() {
    return tokenWith(ACCOUNT.value().toString(), SESSION.value().toString());
  }

  private static Jwt tokenWith(String subject, String session) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("sub", subject);
    claims.put("sid", session);
    return new Jwt(
        "a-token-value",
        Instant.parse("2026-09-20T09:00:00Z"),
        Instant.parse("2026-09-20T09:10:00Z"),
        Map.of("alg", "RS256", "typ", "at+jwt"),
        claims);
  }

  private static java.util.List<String> authoritiesOf(AbstractAuthenticationToken authenticated) {
    return authenticated.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
  }

  /** Answers with whatever the test arranged, and with nothing until it has. */
  private static final class StubAuthenticatedActors implements AuthenticatedActorsUseCase {

    private AuthenticatedActor known;

    void given(Actor actor, boolean passwordChangeRequired) {
      known = new AuthenticatedActor(actor, passwordChangeRequired);
    }

    @Override
    public Optional<AuthenticatedActor> findActor(UserId account, SignInSessionId session) {
      return Optional.ofNullable(known);
    }
  }
}
