package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Role;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is signed in, as this context publishes it.
 *
 * <p>Carries identifiers and kernel types rather than domain entities, like every other published
 * record here: what a caller needs is enough to decide what to show, not the aggregate behind it.
 *
 * <p>The sign-in name is included because the person signed in is entitled to see their own, and it
 * is the one thing on this record that identifies them - which is why {@link #toString()} does not
 * print it.
 *
 * @param userId the account that signed in
 * @param kind whether it belongs to an organisation or to the platform
 * @param organisation the organisation it belongs to, empty for the operator
 * @param role what it may do in that organisation, empty for the operator
 * @param employee the personnel record it is, empty when it is nobody's
 * @param signInName the name it signed in with
 * @param passwordChangeRequired whether the password must be changed before anything else
 */
public record PublishedSession(
    UUID userId,
    AccountKind kind,
    Optional<UUID> organisation,
    Optional<Role> role,
    Optional<UUID> employee,
    String signInName,
    boolean passwordChangeRequired) {

  public PublishedSession {
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(role, "role must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(signInName, "signInName must not be null");
  }

  @Override
  public String toString() {
    return "PublishedSession[userId=%s, kind=%s]".formatted(userId, kind);
  }
}
