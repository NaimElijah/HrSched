package io.github.naimelijah.hrsched.access.adapter.out.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * What the signing key refuses to start on.
 *
 * <p>Every refusal here is a deployment that would otherwise come up and look healthy while signing
 * tokens nobody intended: with no key at all, with a key too short to be worth signing with, or
 * with something that is not a key. Failing at startup is the point — an instance that cannot sign
 * correctly must not serve.
 *
 * <p>Every key in this test is generated in the test. No key material is committed, here or
 * anywhere.
 */
class AccessTokenKeyTest {

  private static final String ISSUER = "hrsched-test";
  private static final String AUDIENCE = "hrsched-api-test";
  private static final Duration LIFETIME = Duration.ofMinutes(10);
  private static final String MALFORMED_KEY =
      "-----BEGIN PRIVATE KEY-----\nnot-a-key\n-----END PRIVATE KEY-----";

  @Test
  void GivenNoKeyAndNoEphemeralPermission_WhenStarting_ThenStartupFails() {
    AccessTokenProperties noKey = properties(null, false);

    assertThatThrownBy(() -> new AccessTokenKey(noKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HRSCHED_SECURITY_TOKEN_PRIVATEKEY");
  }

  @Test
  void GivenNoKeyButEphemeralPermission_WhenStarting_ThenAKeyIsGenerated() {
    AccessTokenKey key = new AccessTokenKey(properties(null, true));

    assertThat(key.publicKey().getModulus().bitLength()).isGreaterThanOrEqualTo(2048);
    assertThat(key.keyId()).isNotBlank();
  }

  /** Two instances must not share a key, or "ephemeral" would be a word with no consequence. */
  @Test
  void GivenTwoEphemeralKeys_WhenComparingThem_ThenTheyAreDifferent() {
    AccessTokenKey one = new AccessTokenKey(properties(null, true));
    AccessTokenKey another = new AccessTokenKey(properties(null, true));

    assertThat(one.keyId()).isNotEqualTo(another.keyId());
  }

  @Test
  void GivenAConfiguredKey_WhenStarting_ThenItsPublicHalfVerifiesWhatItSigns() throws Exception {
    KeyPair generated = rsaKeyPair(3072);
    AccessTokenKey key = new AccessTokenKey(properties(pemOf(generated), false));

    byte[] message = "anything at all".getBytes();
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(key.privateKey());
    signer.update(message);
    byte[] signature = signer.sign();

    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(key.publicKey());
    verifier.update(message);
    assertThat(verifier.verify(signature)).isTrue();
  }

  /** The key id is derived from the key, so the same key always answers to the same name. */
  @Test
  void GivenTheSameConfiguredKeyTwice_WhenStarting_ThenItHasTheSameKeyId() throws Exception {
    String pem = pemOf(rsaKeyPair(2048));

    assertThat(new AccessTokenKey(properties(pem, false)).keyId())
        .isEqualTo(new AccessTokenKey(properties(pem, false)).keyId());
  }

  @Test
  void GivenAKeyShorterThanTheMinimum_WhenStarting_ThenStartupFails() throws Exception {
    AccessTokenProperties tooShort = properties(pemOf(rsaKeyPair(1024)), false);

    assertThatThrownBy(() -> new AccessTokenKey(tooShort))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("2048");
  }

  @Test
  void GivenAMalformedKey_WhenStarting_ThenStartupFailsWithoutEchoingIt() {
    AccessTokenProperties malformed = properties(MALFORMED_KEY, false);

    assertThatThrownBy(() -> new AccessTokenKey(malformed))
        .isInstanceOf(IllegalStateException.class)
        .satisfies(
            refused ->
                assertThat(stackTraceOf(refused))
                    .describedAs("nothing of the submitted key may reach a log")
                    .doesNotContain("not-a-key"));
  }

  @Test
  void GivenAKeyThatIsNotRsa_WhenStarting_ThenStartupFails() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(256);
    AccessTokenProperties notRsa = properties(pemOf(generator.generateKeyPair()), false);

    assertThatThrownBy(() -> new AccessTokenKey(notRsa)).isInstanceOf(IllegalStateException.class);
  }

  /** An ephemeral key is only reached when none was configured; a configured one still wins. */
  @Test
  void GivenAKeyAndEphemeralPermission_WhenStarting_ThenTheConfiguredKeyIsUsed() throws Exception {
    KeyPair generated = rsaKeyPair(2048);
    RSAPublicKey configured = (RSAPublicKey) generated.getPublic();

    AccessTokenKey key = new AccessTokenKey(properties(pemOf(generated), true));

    assertThat(key.publicKey().getModulus()).isEqualTo(configured.getModulus());
  }

  /** A variable set to nothing is the same as one nobody set, and must fail the same way. */
  @Test
  void GivenABlankKeyAndNoEphemeralPermission_WhenStarting_ThenStartupFails() {
    AccessTokenProperties blank = properties("   ", false);

    assertThatThrownBy(() -> new AccessTokenKey(blank)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void GivenNoProperties_WhenStarting_ThenItIsRefused() {
    assertThatThrownBy(() -> new AccessTokenKey(null)).isInstanceOf(NullPointerException.class);
  }

  private static AccessTokenProperties properties(String privateKey, boolean ephemeral) {
    return new AccessTokenProperties(ISSUER, AUDIENCE, LIFETIME, privateKey, ephemeral);
  }

  private static KeyPair rsaKeyPair(int bits) throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(bits);
    return generator.generateKeyPair();
  }

  /** PKCS#8 PEM, the form a deployment supplies through the environment. */
  private static String pemOf(KeyPair pair) {
    return "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
  }

  private static String stackTraceOf(Throwable thrown) {
    StringWriter written = new StringWriter();
    thrown.printStackTrace(new PrintWriter(written));
    return written.toString();
  }
}
