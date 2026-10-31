package io.github.naimelijah.hrsched.access.adapter.in.token;

import io.github.naimelijah.hrsched.access.adapter.out.token.AccessTokenKey;
import io.github.naimelijah.hrsched.access.adapter.out.token.AccessTokenProperties;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Driving adapter verifying access tokens, and the only {@link JwtDecoder} this application has.
 *
 * <p>Accepts exactly one kind of token: one this service signed, for this service, that has not
 * expired, and that names both an account and a session. Everything else is refused. Each check
 * below closes a way of acting as somebody else, so none of them is ceremony:
 *
 * <ul>
 *   <li><strong>The algorithm is pinned</strong>, not read from the token. A verifier that trusts
 *       the header will check an HS256 signature made with the RSA public key — which is public —
 *       and believe it. This is why {@code signatureAlgorithm} is set rather than left open.
 *   <li><strong>The type must be {@code at+jwt}.</strong> Nimbus's own type check is switched off
 *       ({@code validateType(false)}) because it accepts only {@code JWT}; {@link JwtTypeValidator}
 *       replaces it with the stricter question. Without this, anything else this service ever signs
 *       could be spent as an access token.
 *   <li><strong>The issuer and audience must be ours.</strong> A token minted elsewhere, or for
 *       another service that happens to share a key, is not spendable here.
 *   <li><strong>Expiry is required, not merely honoured.</strong> {@link JwtTimestampValidator}
 *       allows a missing {@code exp} by default, which would make a stolen token permanent, so that
 *       default is switched off. Its clock is injected, so expiry is testable arithmetic.
 *   <li><strong>Subject, session and issued-at are required.</strong> A token without a subject
 *       names nobody, and one without a session cannot be revoked, so signing out would leave it
 *       working. Issued-at is enforced before conversion, for the reason given on {@code requiring}
 *       below.
 * </ul>
 *
 * <p>Verifying the signature proves only that this service issued the token. Whether the account
 * still exists, is still enabled, still holds the role it did, and whether its session is still
 * live is a separate question, asked against stored state on every request.
 */
@Component
public class AccessTokenDecoder implements JwtDecoder {

  /**
   * RFC 9068's media type for an access token; the header value this accepts and no other. It must
   * equal the one {@code JwtAccessTokenIssuer} signs, or every issued token is refused.
   */
  private static final String ACCESS_TOKEN_TYPE = "at+jwt";

  /**
   * The session an access token belongs to. Not a registered claim; this service's own. It must
   * equal the name {@code JwtAccessTokenIssuer} writes, or no token names a session.
   */
  private static final String SESSION_CLAIM = "sid";

  private final NimbusJwtDecoder delegate;

  /**
   * @param key the key pair whose public half verifies a signature
   * @param properties the issuer and audience a token must name
   * @param clock what "expired" is measured against
   */
  public AccessTokenDecoder(AccessTokenKey key, AccessTokenProperties properties, Clock clock) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(properties, "properties must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    this.delegate =
        NimbusJwtDecoder.withPublicKey(key.publicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .validateType(false)
            .build();
    this.delegate.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(validators(properties, clock)));
    this.delegate.setClaimSetConverter(
        requiring(JwtClaimNames.IAT, MappedJwtClaimSetConverter.withDefaults(Map.of())));
  }

  /**
   * @param token the bearer token presented on a request
   * @return the verified token and its claims
   * @throws JwtException if it fails any check above; the caller answers 401 and never says which
   */
  @Override
  public Jwt decode(String token) throws JwtException {
    return delegate.decode(token);
  }

  private static List<OAuth2TokenValidator<Jwt>> validators(
      AccessTokenProperties properties, Clock clock) {
    JwtTimestampValidator timestamps = new JwtTimestampValidator();
    timestamps.setClock(clock);
    timestamps.setAllowEmptyExpiryClaim(false);
    JwtTypeValidator type = new JwtTypeValidator(ACCESS_TOKEN_TYPE);
    type.setAllowEmpty(false);
    return List.of(
        timestamps,
        type,
        new JwtIssuerValidator(properties.issuer()),
        new JwtAudienceValidator(properties.audience()),
        required(JwtClaimNames.SUB),
        required(SESSION_CLAIM));
  }

  /** A claim the token is refused without, whatever its value. */
  private static OAuth2TokenValidator<Jwt> required(String claim) {
    return new JwtClaimValidator<Object>(claim, Objects::nonNull);
  }

  /**
   * Refuses a token that does not carry a claim, before anything has a chance to supply one.
   *
   * <p>Needed for {@code iat} specifically. Spring's default claim converter fills a missing {@code
   * iat} in from {@code exp} minus one second — read from the jar, not assumed — so a validator
   * asking for one on the converted claims would always find it, and "required" would be a word
   * with no effect. The raw claims are what this looks at.
   */
  private static Converter<Map<String, Object>, Map<String, Object>> requiring(
      String claim, Converter<Map<String, Object>, Map<String, Object>> conversion) {
    return source -> {
      if (!source.containsKey(claim)) {
        throw new BadJwtException("The token carries no %s claim".formatted(claim));
      }
      return conversion.convert(source);
    };
  }
}
