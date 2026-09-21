package io.github.naimelijah.hrsched.access.adapter.out.token;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Wires the signing key and the encoder that uses it.
 *
 * <p>Lives in the adapter package rather than in {@code configuration}, because everything it
 * builds is token knowledge and the architecture rules keep {@code configuration} free of adapter
 * types. Being annotated {@code @Configuration} does not move a class into that layer; the package
 * does, and this one is an adapter.
 *
 * <p>The algorithm is fixed here, at the one place a token is made, rather than being a property.
 * An algorithm a deployment can choose is an algorithm a deployment can choose badly, and the
 * decoder has to pin the same one anyway for the signature to mean anything.
 */
@Configuration(proxyBeanMethods = false)
public class AccessTokenSigning {

  /**
   * @param properties where the key comes from
   * @return the key pair this instance signs with; construction fails if there is none
   */
  @Bean
  public AccessTokenKey accessTokenKey(AccessTokenProperties properties) {
    return new AccessTokenKey(properties);
  }

  /**
   * The encoder is exposed as a bean of Spring Security's own type rather than kept private to the
   * issuer, so that a test can sign a token the running application would accept without having to
   * reproduce how one is signed.
   *
   * @param key the key pair to sign with
   * @return an encoder producing RS256 signatures under that key's id
   */
  @Bean
  public JwtEncoder jwtEncoder(AccessTokenKey key) {
    return NimbusJwtEncoder.withKeyPair(key.publicKey(), key.privateKey())
        .algorithm(SignatureAlgorithm.RS256)
        .jwkPostProcessor(jwk -> jwk.keyID(key.keyId()))
        .build();
  }
}
