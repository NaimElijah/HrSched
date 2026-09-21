package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import java.util.Optional;

/**
 * The body of a request to create an account.
 *
 * <p>There is no organisation field, and there must not be: these routes act in the organisation
 * the token names, so an Admin cannot address another one by typing its identifier.
 *
 * <p>{@link #toString()} shows the role and nothing else.
 *
 * @param signInName the name the account will sign in with
 * @param temporaryPassword the password it is given, which its owner must replace
 * @param role what it may do
 * @param employee the personnel record it is, null for somebody who is never rostered
 */
public record CreateAccountRequest(
    String signInName, String temporaryPassword, Role role, String employee) {

  /**
   * @return the sign-in name given
   * @throws MalformedRequestException if it is absent, which Jackson maps to null rather than
   *     rejecting
   */
  String requiredSignInName() {
    if (signInName == null) {
      throw new MalformedRequestException("signInName is required");
    }
    return signInName;
  }

  /**
   * @return the temporary password given
   * @throws MalformedRequestException if it is absent
   */
  String requiredTemporaryPassword() {
    if (temporaryPassword == null) {
      throw new MalformedRequestException("temporaryPassword is required");
    }
    return temporaryPassword;
  }

  /**
   * @return the role given
   * @throws MalformedRequestException if it is absent
   */
  Role requiredRole() {
    if (role == null) {
      throw new MalformedRequestException("role is required");
    }
    return role;
  }

  /**
   * @return the employee named, empty when the account belongs to nobody rostered
   * @throws MalformedRequestException if what was sent is not a UUID
   */
  Optional<EmployeeId> linkedEmployee() {
    return EmployeeLink.parse(employee);
  }

  @Override
  public String toString() {
    return "CreateAccountRequest[role=%s, rest=redacted]".formatted(role);
  }
}
