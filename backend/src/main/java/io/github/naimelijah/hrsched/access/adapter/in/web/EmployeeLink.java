package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the optional employee link out of a request body.
 *
 * <p>Shared by the two requests that carry one, because an identifier that parses in one and not
 * the other would be a difference nobody intended.
 *
 * <p>A malformed identifier is a 400 of this adapter's own making rather than a binding failure, so
 * the message names the field instead of quoting a body that also contains a password.
 */
final class EmployeeLink {

  private EmployeeLink() {}

  /**
   * @param employee what was sent, or null when the field was absent
   * @return the employee named, empty when nothing was
   * @throws MalformedRequestException if what was sent is not a UUID
   */
  static Optional<EmployeeId> parse(String employee) {
    if (employee == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(new EmployeeId(UUID.fromString(employee)));
    } catch (IllegalArgumentException malformed) {
      throw new MalformedRequestException("employee must be a UUID");
    }
  }
}
