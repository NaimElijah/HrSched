package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * An account as an Admin of its organisation sees it.
 *
 * <p>Carries no password hash and no failure count. The hash is a credential, and how many times
 * somebody has mistyped their own password is about them rather than about what they may do; {@code
 * locked} says the only part of it an Admin has to act on.
 *
 * <p>The sign-in name is here because administering accounts is impossible without it — it is how
 * an Admin tells two colleagues apart. It never reaches a log: {@code AccessAuditLog} carries
 * identifiers only, and this type never goes there.
 *
 * @param id the account
 * @param signInName the name it signs in with
 * @param role what it may do in its organisation
 * @param employee the personnel record it is, empty for somebody who is never rostered, or for an
 *     Employee account stored unlinked before R34, which is disabled until it is linked
 * @param active whether it may sign in and act
 * @param passwordChangeRequired whether it still holds a password somebody else chose
 * @param locked whether too many consecutive failures have locked it, as of the moment asked
 * @param createdAt when it was created
 */
public record PublishedAccount(
    UserId id,
    String signInName,
    Role role,
    Optional<EmployeeId> employee,
    boolean active,
    boolean passwordChangeRequired,
    boolean locked,
    Instant createdAt) {

  public PublishedAccount {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(signInName, "signInName must not be null");
    Objects.requireNonNull(role, "role must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  /**
   * Never prints the sign-in name: this record is published data, and a log is not its audience.
   */
  @Override
  public String toString() {
    return "PublishedAccount[id=%s, role=%s, active=%s]".formatted(id.value(), role, active);
  }
}
