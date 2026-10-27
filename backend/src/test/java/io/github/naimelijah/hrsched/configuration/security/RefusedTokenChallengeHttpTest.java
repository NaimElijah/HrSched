package io.github.naimelijah.hrsched.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.naimelijah.hrsched.access.adapter.out.token.AccessTokenKey;
import io.github.naimelijah.hrsched.access.adapter.out.token.AccessTokenProperties;
import io.github.naimelijah.hrsched.access.adapter.out.token.JwtAccessTokenIssuer;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Specification that a refused access token is refused without saying why (issue #250).
 *
 * <p>The decoder's own message — "Jwt expired at …", "Invalid signature", which claim was missing —
 * used to travel to the caller in the challenge's {@code error_description}. Each one is a free
 * answer to somebody probing the verifier: that the signature was good but the clock was not, or
 * which of its checks a forged token got past. So every refused token gets the same challenge and
 * the same body, whatever refused it; the reason is the server's to know.
 *
 * <p>The three tokens each fail for exactly one reason. The expired one is signed with this
 * context's own key; the one with a bad signature carries another real token's signature; the one
 * missing a claim is signed with this context's own key but names no session. None names an account
 * or session that exists, and none has to: each is refused before anything is looked up.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class RefusedTokenChallengeHttpTest {

  /**
   * Past the 60 seconds by which {@code JwtTimestampValidator} lets an expiry slip by default, so a
   * token this far beyond its lifetime is expired by any reading of the clock.
   */
  private static final Duration PAST_THE_CLOCK_SKEW = Duration.ofMinutes(5);

  private static final String ACCESS_TOKEN_TYPE = "at+jwt";

  /** A route that needs a token and reveals nothing else, so the 401 can only be the token's. */
  private static final String SESSION = "/api/auth/session";

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtEncoder encoder;
  @Autowired private AccessTokenKey key;
  @Autowired private AccessTokenProperties tokenProperties;

  @Test
  void GivenTokensRefusedForDifferentReasons_WhenEachIsPresented_ThenEveryChallengeIsIdentical()
      throws Exception {
    MockHttpServletResponse expired = present(anExpiredToken());
    MockHttpServletResponse badSignature = present(aTokenWithAnotherTokensSignature());
    MockHttpServletResponse missingClaim = present(aTokenNamingNoSession());

    assertThat(List.of(expired.getStatus(), badSignature.getStatus(), missingClaim.getStatus()))
        .describedAs("arranged: every token is refused")
        .containsOnly(401);
    assertSoftly(
        softly -> {
          softly
              .assertThat(badSignature.getHeader(HttpHeaders.WWW_AUTHENTICATE))
              .describedAs("bad signature's challenge, beside the expired token's")
              .isEqualTo(expired.getHeader(HttpHeaders.WWW_AUTHENTICATE));
          softly
              .assertThat(missingClaim.getHeader(HttpHeaders.WWW_AUTHENTICATE))
              .describedAs("missing claim's challenge, beside the expired token's")
              .isEqualTo(expired.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        });
  }

  @Test
  void GivenTokensRefusedForDifferentReasons_WhenEachIsPresented_ThenEveryBodyIsIdentical()
      throws Exception {
    MockHttpServletResponse expired = present(anExpiredToken());
    MockHttpServletResponse badSignature = present(aTokenWithAnotherTokensSignature());
    MockHttpServletResponse missingClaim = present(aTokenNamingNoSession());

    assertThat(List.of(expired.getStatus(), badSignature.getStatus(), missingClaim.getStatus()))
        .describedAs("arranged: every token is refused")
        .containsOnly(401);
    String expiredBody = expired.getContentAsString();
    String badSignatureBody = badSignature.getContentAsString();
    String missingClaimBody = missingClaim.getContentAsString();
    assertSoftly(
        softly -> {
          softly
              .assertThat(badSignatureBody)
              .describedAs("bad signature's body, beside the expired token's")
              .isEqualTo(expiredBody);
          softly
              .assertThat(missingClaimBody)
              .describedAs("missing claim's body, beside the expired token's")
              .isEqualTo(expiredBody);
        });
  }

  private MockHttpServletResponse present(String token) throws Exception {
    return mockMvc
        .perform(get(SESSION).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andReturn()
        .getResponse();
  }

  /** Validly signed by this context's key, and wrong only in its age. */
  private String anExpiredToken() {
    Instant issued = Instant.now().minus(tokenProperties.lifetime()).minus(PAST_THE_CLOCK_SKEW);
    return new JwtAccessTokenIssuer(
            encoder, key, tokenProperties, Clock.fixed(issued, ZoneOffset.UTC))
        .issue(UserId.newId(), SignInSessionId.newId())
        .value();
  }

  /**
   * One valid token's header and claims under another valid token's signature. Both halves are
   * genuine, so the only thing wrong is that the signature does not match what it signs.
   */
  private String aTokenWithAnotherTokensSignature() {
    JwtAccessTokenIssuer issuer =
        new JwtAccessTokenIssuer(encoder, key, tokenProperties, Clock.systemUTC());
    String signed = issuer.issue(UserId.newId(), SignInSessionId.newId()).value();
    String other = issuer.issue(UserId.newId(), SignInSessionId.newId()).value();
    return signed.substring(0, signed.lastIndexOf('.')) + other.substring(other.lastIndexOf('.'));
  }

  /** Signed by this context's key and in date, but carrying no session claim. */
  private String aTokenNamingNoSession() {
    Instant now = Instant.now();
    JwsHeader header =
        JwsHeader.with(SignatureAlgorithm.RS256).type(ACCESS_TOKEN_TYPE).keyId(key.keyId()).build();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(tokenProperties.issuer())
            .audience(List.of(tokenProperties.audience()))
            .subject(UserId.newId().value().toString())
            .issuedAt(now)
            .expiresAt(now.plus(tokenProperties.lifetime()))
            .build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
