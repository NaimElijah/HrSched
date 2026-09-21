package io.github.naimelijah.hrsched.access.adapter.in.token;

import io.github.naimelijah.hrsched.access.application.port.in.AuthenticatedActor;
import io.github.naimelijah.hrsched.access.application.port.in.AuthenticatedActorsUseCase;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Turns a verified token into the caller it names — if that caller still exists and is still signed
 * in.
 *
 * <p>This is where a signature stops being enough. The decoder has already proved the token is one
 * this service issued and has not expired; what it cannot know is whether the account has since
 * been disabled, whether its role changed, or whether the session was signed out a second ago. That
 * is asked here, against stored state, on every request.
 *
 * <p>Every way of failing answers the same way: a malformed claim, an account nobody has, a session
 * belonging to somebody else, a revoked session and a disabled account all raise the same refusal
 * with the same message. The caller is told a token was not accepted and nothing else, because
 * which of those it was is a fact about somebody's account.
 *
 * <p>A database failure is deliberately <em>not</em> caught. Failing to read is not the same as
 * reading that somebody may not act, and a caller must never be let in because a query broke.
 */
@Component
public class ActorAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  /** The session an access token belongs to. Not a registered claim; this service's own. */
  private static final String SESSION_CLAIM = "sid";

  /** One sentence for every way this can fail, so that none of them can be told from another. */
  private static final String NOT_ACCEPTED = "The access token was not accepted";

  private final AuthenticatedActorsUseCase actors;

  public ActorAuthenticationConverter(AuthenticatedActorsUseCase actors) {
    this.actors = Objects.requireNonNull(actors, "actors must not be null");
  }

  /**
   * @param token a token the decoder has already verified
   * @return the authenticated caller
   * @throws InvalidBearerTokenException if the token should no longer be believed; the resource
   *     server turns this into a 401
   */
  @Override
  public AbstractAuthenticationToken convert(Jwt token) {
    UserId account = new UserId(uuidClaim(token.getSubject()));
    SignInSessionId session = new SignInSessionId(uuidClaim(token.getClaimAsString(SESSION_CLAIM)));
    AuthenticatedActor authenticated =
        actors
            .findActor(account, session)
            .orElseThrow(() -> new InvalidBearerTokenException(NOT_ACCEPTED));
    return new ActorAuthentication(
        authenticated.actor(), token, authenticated.passwordChangeRequired());
  }

  /**
   * The decoder requires both claims to be present, so a value that is not a UUID is a token this
   * service would not have signed — refused exactly as an unknown account is.
   */
  private static UUID uuidClaim(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException notAnIdentifier) {
      throw new InvalidBearerTokenException(NOT_ACCEPTED);
    }
  }
}
