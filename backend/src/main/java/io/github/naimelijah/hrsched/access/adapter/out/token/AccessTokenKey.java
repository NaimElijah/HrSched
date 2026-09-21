package io.github.naimelijah.hrsched.access.adapter.out.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RSA key pair every access token is signed with, and the name that key answers to.
 *
 * <p>An instance either has a usable key or does not exist. Everything that can be wrong with a
 * signing key is decided here, at startup, because the alternative is an instance that comes up
 * looking healthy and then signs tokens nobody can verify — or worse, verifies tokens nobody should
 * have been able to mint. A deployment that cannot sign correctly must not serve.
 *
 * <p>The key is supplied as a PKCS#8 PEM through the environment. When none is supplied, startup
 * fails, unless the ephemeral flag explicitly permits a throwaway key: that flag is set by {@code
 * spring-boot:run} and by the test configuration, and never by a default, so a real deployment that
 * forgot its key stops rather than inventing one. An invented key would mean every restart
 * invalidating every session, and two instances refusing each other's tokens.
 *
 * <p>Refusals name the environment variable to set and nothing else. No message here, and no
 * exception it wraps, carries key material.
 *
 * <p>The key id is the RFC 7638 thumbprint of the public key, so it is derived rather than chosen:
 * the same key always answers to the same name, on every instance, with nothing to keep in step.
 */
public final class AccessTokenKey {

  private static final Logger log = LoggerFactory.getLogger(AccessTokenKey.class);

  /**
   * The shortest modulus accepted. 2048 bits is the floor current guidance puts on RSA; below it
   * the signature is decoration.
   */
  private static final int MINIMUM_KEY_SIZE_BITS = 2048;

  /** The size of a generated throwaway key. Larger than the floor, because generating is cheap. */
  private static final int GENERATED_KEY_SIZE_BITS = 3072;

  private static final String RSA = "RSA";
  private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
  private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";
  private static final String PRIVATE_KEY_VARIABLE = "HRSCHED_SECURITY_TOKEN_PRIVATEKEY";
  private static final String EPHEMERAL_PROPERTY = "hrsched.security.token.ephemeral";

  private static final String NO_KEY =
      "No access-token signing key is configured. Set "
          + PRIVATE_KEY_VARIABLE
          + " to a PKCS#8 PEM RSA private key of at least "
          + MINIMUM_KEY_SIZE_BITS
          + " bits, or set "
          + EPHEMERAL_PROPERTY
          + "=true for local development, where a throwaway key is generated at startup";

  private static final String EPHEMERAL_KEY_IN_USE =
      "Signing access tokens with an ephemeral key generated at startup: tokens do not survive a"
          + " restart, and instances cannot verify one another's. Intended for local development"
          + " only";

  private final RSAPublicKey publicKey;
  private final RSAPrivateKey privateKey;
  private final String keyId;

  /**
   * @param properties where the key comes from, never {@code null}
   * @throws IllegalStateException if no key is configured and none may be generated, or if the
   *     configured key is not RSA, is malformed, or is shorter than the minimum
   */
  public AccessTokenKey(AccessTokenProperties properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    KeyPair pair = keyPairFrom(properties);
    this.publicKey = (RSAPublicKey) pair.getPublic();
    this.privateKey = (RSAPrivateKey) pair.getPrivate();
    this.keyId = thumbprintOf(publicKey);
  }

  /**
   * @return the half that verifies a signature, which anyone may hold
   */
  public RSAPublicKey publicKey() {
    return publicKey;
  }

  /**
   * @return the half that makes a signature, which nothing outside this process may see
   */
  public RSAPrivateKey privateKey() {
    return privateKey;
  }

  /**
   * @return the RFC 7638 thumbprint naming this key, put in every token's {@code kid} header
   */
  public String keyId() {
    return keyId;
  }

  private static KeyPair keyPairFrom(AccessTokenProperties properties) {
    String pem = properties.privateKey();
    if (pem != null && !pem.isBlank()) {
      return configured(pem);
    }
    if (!properties.ephemeral()) {
      throw new IllegalStateException(NO_KEY);
    }
    log.warn(EPHEMERAL_KEY_IN_USE);
    return generated();
  }

  private static KeyPair configured(String pem) {
    RSAPrivateCrtKey privateKey = parse(pem);
    if (privateKey.getModulus().bitLength() < MINIMUM_KEY_SIZE_BITS) {
      throw new IllegalStateException(
          "The access-token signing key in %s is shorter than the %d bits required"
              .formatted(PRIVATE_KEY_VARIABLE, MINIMUM_KEY_SIZE_BITS));
    }
    return new KeyPair(publicHalfOf(privateKey), privateKey);
  }

  /**
   * Parses the PEM with the JDK alone. The private key is a CRT key or this refuses it: deriving
   * the public half needs the public exponent, which only the CRT form carries, and a key whose
   * public half cannot be derived is a key nothing could ever verify against.
   *
   * <p>No cause is chained from any refusal below, which is the one place this class departs from
   * enriching and rethrowing. A base64 decoder or a JCA provider is free to quote the bytes it
   * choked on, and those bytes are the signing key; a startup log is exactly where they must not
   * appear. What an operator needs — which variable is wrong and in what way — is in the message.
   */
  private static RSAPrivateCrtKey parse(String pem) {
    byte[] encoded;
    try {
      encoded =
          Base64.getMimeDecoder()
              .decode(pem.replace(PEM_HEADER, "").replace(PEM_FOOTER, "").strip());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "The access-token signing key in %s is not valid base64".formatted(PRIVATE_KEY_VARIABLE));
    }
    PrivateKey parsed;
    try {
      parsed = KeyFactory.getInstance(RSA).generatePrivate(new PKCS8EncodedKeySpec(encoded));
    } catch (InvalidKeySpecException | NoSuchAlgorithmException refused) {
      throw new IllegalStateException(
          "The access-token signing key in %s is not a PKCS#8 RSA private key"
              .formatted(PRIVATE_KEY_VARIABLE));
    }
    if (parsed instanceof RSAPrivateCrtKey crt) {
      return crt;
    }
    throw new IllegalStateException(
        "The access-token signing key in %s must be an RSA private key in CRT form"
            .formatted(PRIVATE_KEY_VARIABLE));
  }

  private static RSAPublicKey publicHalfOf(RSAPrivateCrtKey privateKey) {
    try {
      return (RSAPublicKey)
          KeyFactory.getInstance(RSA)
              .generatePublic(
                  new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
    } catch (InvalidKeySpecException | NoSuchAlgorithmException refused) {
      throw new IllegalStateException(
          "The public half of the access-token signing key in %s could not be derived"
              .formatted(PRIVATE_KEY_VARIABLE));
    }
  }

  private static KeyPair generated() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA);
      generator.initialize(GENERATED_KEY_SIZE_BITS);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("This JVM cannot generate RSA keys", impossible);
    }
  }

  private static String thumbprintOf(RSAPublicKey publicKey) {
    try {
      return new RSAKey.Builder(publicKey).build().computeThumbprint().toString();
    } catch (JOSEException refused) {
      throw new IllegalStateException("The access-token signing key could not be named", refused);
    }
  }
}
