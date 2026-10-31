package io.github.naimelijah.hrsched.access.adapter.out.token;

import io.github.naimelijah.hrsched.access.application.port.out.AccessTokenIssuer;
import io.github.naimelijah.hrsched.access.application.port.out.IssuedAccessToken;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Driven adapter signing access tokens as RS256 JWTs.
 *
 * <p>The token carries only what deciding a request needs and nothing a person could be identified
 * by: the account, the session, and when it was issued and expires. No name, no role and no
 * organisation. Roles deliberately stay out of it, because a token is believed until it expires,
 * and demoting or disabling somebody has to bite on their next request rather than ten minutes
 * later (ADR-011).
 *
 * <p>{@code typ} is {@code at+jwt} so that an access token cannot be spent as anything else this
 * service might sign, and {@code kid} names the key, so a verifier can tell which key to check
 * against once there is more than one.
 *
 * <p>Time comes from the injected {@link Clock}, so what a token's lifetime means is an input to a
 * test rather than a fact about when the suite happened to run.
 */
@Component
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

  /**
   * RFC 9068's media type for an access token, put in the header and required by the decoder. The
   * decoder holds its own copy of the value, which must stay equal to this one.
   */
  private static final String ACCESS_TOKEN_TYPE = "at+jwt";

  /**
   * The session an access token belongs to. Not a registered claim; this service's own. The decoder
   * and the converter in {@code adapter.in.token} read it by their own copies of the name, which
   * must stay equal to this one.
   */
  private static final String SESSION_CLAIM = "sid";

  private final JwtEncoder encoder;
  private final AccessTokenKey key;
  private final AccessTokenProperties properties;
  private final Clock clock;

  public JwtAccessTokenIssuer(
      JwtEncoder encoder, AccessTokenKey key, AccessTokenProperties properties, Clock clock) {
    this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
    this.key = Objects.requireNonNull(key, "key must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public IssuedAccessToken issue(UserId account, SignInSessionId session) {
    Objects.requireNonNull(account, "account must not be null");
    Objects.requireNonNull(session, "session must not be null");
    Instant issuedAt = clock.instant();
    Instant expiresAt = issuedAt.plus(properties.lifetime());
    JwsHeader header =
        JwsHeader.with(SignatureAlgorithm.RS256).type(ACCESS_TOKEN_TYPE).keyId(key.keyId()).build();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .audience(List.of(properties.audience()))
            .subject(account.value().toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim(SESSION_CLAIM, session.value().toString())
            .build();
    return new IssuedAccessToken(
        encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
  }
}
