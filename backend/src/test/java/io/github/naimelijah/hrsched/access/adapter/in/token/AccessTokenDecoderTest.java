package io.github.naimelijah.hrsched.access.adapter.in.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.github.naimelijah.hrsched.access.adapter.out.token.AccessTokenKey;
import io.github.naimelijah.hrsched.access.adapter.out.token.AccessTokenProperties;
import io.github.naimelijah.hrsched.access.adapter.out.token.AccessTokenSigning;
import io.github.naimelijah.hrsched.access.adapter.out.token.JwtAccessTokenIssuer;
import io.github.naimelijah.hrsched.access.application.port.out.IssuedAccessToken;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * What this decoder accepts, and the many more things it refuses.
 *
 * <p>Every refusal below is a way somebody could otherwise act as a user they are not. The
 * algorithm-confusion case is the sharpest: a verifier that trusts the token's own header will
 * happily check an HS256 signature made with the public key everyone is allowed to know, so the
 * algorithm is pinned in the decoder rather than read from the token.
 *
 * <p>No Spring context. The issuer and the decoder are built here exactly as their beans are, over
 * an ephemeral key generated for this test, with a fixed clock so that expiry is arithmetic rather
 * than timing.
 */
class AccessTokenDecoderTest {

  private static final String ISSUER = "hrsched-test";
  private static final String AUDIENCE = "hrsched-api-test";
  private static final Duration LIFETIME = Duration.ofMinutes(10);
  private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");
  private static final UserId ACCOUNT = UserId.newId();
  private static final SignInSessionId SESSION = SignInSessionId.newId();
  private static final String SESSION_CLAIM = "sid";
  private static final String ACCESS_TOKEN_TYPE = "at+jwt";

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final AccessTokenProperties properties =
      new AccessTokenProperties(ISSUER, AUDIENCE, LIFETIME, null, true);
  private final AccessTokenSigning signing = new AccessTokenSigning();
  private final AccessTokenKey key = signing.accessTokenKey(properties);
  private final JwtEncoder encoder = signing.jwtEncoder(key);
  private final JwtAccessTokenIssuer issuer =
      new JwtAccessTokenIssuer(encoder, key, properties, clock);
  private final AccessTokenDecoder decoder = new AccessTokenDecoder(key, properties, clock);

  @Test
  void GivenATokenThisIssuerSigned_WhenDecoding_ThenItsSubjectAndSessionAreRead() {
    String token = issuer.issue(ACCOUNT, SESSION).value();

    Jwt decoded = decoder.decode(token);

    assertThat(decoded.getSubject()).isEqualTo(ACCOUNT.value().toString());
    assertThat(decoded.getClaimAsString(SESSION_CLAIM)).isEqualTo(SESSION.value().toString());
    assertThat(decoded.getHeaders().get("typ")).isEqualTo(ACCESS_TOKEN_TYPE);
    assertThat(decoded.getHeaders().get("kid")).isEqualTo(key.keyId());
  }

  @Test
  void GivenAnIssuedToken_WhenInspected_ThenItExpiresAfterTheConfiguredLifetime() {
    IssuedAccessToken issued = issuer.issue(ACCOUNT, SESSION);

    assertThat(issued.expiresAt()).isEqualTo(NOW.plus(LIFETIME));
    assertThat(decoder.decode(issued.value()).getExpiresAt()).isEqualTo(NOW.plus(LIFETIME));
  }

  /** The value is the credential, so printing one must not put it in a log. */
  @Test
  void GivenAnIssuedToken_WhenPrinted_ThenTheTokenValueIsNotShown() {
    IssuedAccessToken issued = issuer.issue(ACCOUNT, SESSION);

    assertThat(issued.toString()).doesNotContain(issued.value());
  }

  @Test
  void GivenATokenSignedWithAnotherKey_WhenDecoding_ThenItIsRefused() throws Exception {
    AccessTokenKey somebodyElse = signing.accessTokenKey(properties);
    String token = signedWith(somebodyElse.privateKey(), ACCESS_TOKEN_TYPE, validClaims().build());

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  /**
   * An unsigned token, offered as though the absence of a signature were an algorithm. Accepting
   * one would let anybody mint any identity they liked.
   */
  @Test
  void GivenAnAlgNoneToken_WhenDecoding_ThenItIsRefused() {
    String token =
        new PlainJWT(
                new PlainHeader.Builder().type(new JOSEObjectType(ACCESS_TOKEN_TYPE)).build(),
                validClaims().build())
            .serialize();

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  /**
   * The classic confusion attack: the token claims to be HMAC-signed, and the "secret" is the RSA
   * public key, which is not a secret at all. A decoder that took the algorithm from the token
   * would verify this happily.
   */
  @Test
  void GivenATokenClaimingAnotherAlgorithm_WhenDecoding_ThenItIsRefused() throws Exception {
    SignedJWT forged =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(new JOSEObjectType(ACCESS_TOKEN_TYPE))
                .build(),
            validClaims().build());
    forged.sign(new MACSigner(key.publicKey().getEncoded()));
    String token = forged.serialize();

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void GivenATamperedPayload_WhenDecoding_ThenItIsRefused() {
    String[] parts = issuer.issue(ACCOUNT, SESSION).value().split("\\.");
    JWTClaimsSet somebodyElse = validClaims().subject(UUID.randomUUID().toString()).build();
    String token = parts[0] + "." + Base64URL.encode(somebodyElse.toString()) + "." + parts[2];

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void GivenAnExpiredToken_WhenDecoding_ThenItIsRefused() {
    Clock anHourAgo = Clock.fixed(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC);
    String token =
        new JwtAccessTokenIssuer(encoder, key, properties, anHourAgo)
            .issue(ACCOUNT, SESSION)
            .value();

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  /** Without an expiry a stolen token never stops working, so an absent one is not "no limit". */
  @Test
  void GivenATokenWithoutAnExpiry_WhenDecoding_ThenItIsRefused() throws Exception {
    String token =
        signedWith(key.privateKey(), ACCESS_TOKEN_TYPE, validClaims().expirationTime(null).build());

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void GivenATokenWithoutAnIssuedAt_WhenDecoding_ThenItIsRefused() throws Exception {
    String token =
        signedWith(key.privateKey(), ACCESS_TOKEN_TYPE, validClaims().issueTime(null).build());

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void GivenATokenFromAnotherIssuer_WhenDecoding_ThenItIsRefused() throws Exception {
    String token =
        signedWith(
            key.privateKey(), ACCESS_TOKEN_TYPE, validClaims().issuer("somewhere-else").build());

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  /** A token minted for another service must not be spendable here. */
  @Test
  void GivenATokenForAnotherAudience_WhenDecoding_ThenItIsRefused() throws Exception {
    String token =
        signedWith(
            key.privateKey(), ACCESS_TOKEN_TYPE, validClaims().audience("another-api").build());

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  /** Without a session claim nothing can be revoked, so a token without one is not a session. */
  @Test
  void GivenATokenWithoutASessionClaim_WhenDecoding_ThenItIsRefused() throws Exception {
    String token =
        signedWith(
            key.privateKey(), ACCESS_TOKEN_TYPE, validClaims().claim(SESSION_CLAIM, null).build());

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void GivenATokenWithoutASubject_WhenDecoding_ThenItIsRefused() throws Exception {
    String token =
        signedWith(key.privateKey(), ACCESS_TOKEN_TYPE, validClaims().subject(null).build());

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  /**
   * A refresh token, an identity token or anything else this service signs for another purpose must
   * not be spendable as an access token.
   */
  @Test
  void GivenATokenOfAnotherType_WhenDecoding_ThenItIsRefused() throws Exception {
    String token = signedWith(key.privateKey(), "JWT", validClaims().build());

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  private JWTClaimsSet.Builder validClaims() {
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject(ACCOUNT.value().toString())
        .issueTime(Date.from(NOW))
        .expirationTime(Date.from(NOW.plus(LIFETIME)))
        .claim(SESSION_CLAIM, SESSION.value().toString());
  }

  private String signedWith(RSAPrivateKey privateKey, String type, JWTClaimsSet claims)
      throws Exception {
    SignedJWT signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType(type))
                .keyID(key.keyId())
                .build(),
            claims);
    signed.sign(new RSASSASigner(privateKey));
    return signed.serialize();
  }
}
