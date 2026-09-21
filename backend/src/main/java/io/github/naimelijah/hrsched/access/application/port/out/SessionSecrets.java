package io.github.naimelijah.hrsched.access.application.port.out;

/**
 * Driven port: making refresh tokens, and recognising one that comes back.
 *
 * <p>A port rather than a utility because it is where randomness enters, and randomness is exactly
 * the thing a use case must not reach for on its own. How many bits a token carries, and what
 * digests it, are the adapter's business.
 *
 * <p>There is deliberately no way back from a digest to a value. Recognising a token means
 * digesting what was presented and looking that up, so this system never needs to hold one.
 */
public interface SessionSecrets {

  /**
   * @return a new refresh token: the value to hand to the client, and the digest to store
   */
  RefreshTokenSecret generate();

  /**
   * @param value a refresh token somebody presented
   * @return its digest, to look up against what is stored
   */
  byte[] digestOf(String value);
}
