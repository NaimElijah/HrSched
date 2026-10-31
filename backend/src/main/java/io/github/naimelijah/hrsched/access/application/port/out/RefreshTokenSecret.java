package io.github.naimelijah.hrsched.access.application.port.out;

import java.util.Objects;

/**
 * A freshly minted refresh token: the value handed to the client, and the digest that is stored.
 *
 * <p>The two travel together exactly once, at the moment a token is made, and then part company for
 * good. The client keeps the value; this system keeps only the digest, so a database that leaks
 * hands over nothing anybody can present. Nothing here can turn a digest back into a value.
 *
 * <p>The digest is copied in and out. A byte array is mutable, and a caller that kept a reference
 * could change what this claims to hold after the fact.
 *
 * <p>{@link #toString()} reveals neither: the value is a credential, and the digest is what
 * identifies a stored one.
 *
 * @param value the token the client will present, base64url with no padding
 * @param digest its SHA-256 digest, which is what gets stored
 */
public record RefreshTokenSecret(String value, byte[] digest) {

  /**
   * @throws NullPointerException if either component is null
   * @throws IllegalArgumentException if {@code value} is blank or {@code digest} is empty
   */
  public RefreshTokenSecret {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(digest, "digest must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("A refresh token must not be blank");
    }
    if (digest.length == 0) {
      throw new IllegalArgumentException("A refresh token digest must not be empty");
    }
    digest = digest.clone();
  }

  @Override
  public byte[] digest() {
    return digest.clone();
  }

  @Override
  public String toString() {
    return "RefreshTokenSecret[redacted]";
  }
}
