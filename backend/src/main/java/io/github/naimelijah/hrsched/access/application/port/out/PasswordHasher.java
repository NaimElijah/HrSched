package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;

/**
 * Driven port: turning a password into something storable, and checking one against it.
 *
 * <p>Says nothing about the algorithm, on purpose. Which function hashes a password, and how much
 * work it costs, are decisions that have to be changeable without the domain noticing — the whole
 * reason a stored hash carries its own algorithm id.
 *
 * <p>The password is a {@code String} rather than a domain type because it is never stored, never
 * compared here and never travels: it arrives at the boundary, is hashed or checked, and is
 * forgotten. A value object would invite somebody to keep one.
 */
public interface PasswordHasher {

  /**
   * @param rawPassword the password somebody chose, never {@code null}
   * @return its encoded hash, carrying the algorithm that produced it
   * @throws IllegalArgumentException if the password is longer than {@link #maximumBytes()}; the
   *     message never contains the password
   */
  PasswordHash hash(String rawPassword);

  /**
   * @param rawPassword the password submitted
   * @param hash the stored hash to check it against
   * @return whether they match; false rather than an exception, because a wrong password is an
   *     ordinary outcome and not an error
   */
  boolean matches(String rawPassword, PasswordHash hash);

  /**
   * The longest password the algorithm reads, in UTF-8 bytes.
   *
   * <p>Published because a limit the algorithm imposes has to become a rule the boundary enforces:
   * an algorithm that silently ignores everything past a certain length turns a long passphrase
   * into a shorter one its owner never chose.
   *
   * @return the greatest number of UTF-8 bytes a password may have
   */
  int maximumBytes();
}
