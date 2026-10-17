package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import java.util.Optional;

/**
 * The body of a request to change what an account is inside its organisation.
 *
 * <p>The employee link is part of it rather than a request of its own: an account is somebody in a
 * role, and setting the two separately would leave a moment where they disagreed. An absent {@code
 * employee} therefore unlinks the account, and is not the same as leaving it alone; an Employee
 * account refuses it (R34).
 *
 * @param role what the account may do from now on
 * @param employee the personnel record it is, null to unlink it
 */
public record ChangeMembershipRequest(Role role, String employee) {

  /**
   * @return the role given
   * @throws MalformedRequestException if it is absent, which Jackson maps to null rather than
   *     rejecting
   */
  Role requiredRole() {
    if (role == null) {
      throw new MalformedRequestException("role is required");
    }
    return role;
  }

  /**
   * @return the employee named, empty to unlink
   * @throws MalformedRequestException if what was sent is not a UUID
   */
  Optional<EmployeeId> linkedEmployee() {
    return EmployeeLink.parse(employee);
  }
}
