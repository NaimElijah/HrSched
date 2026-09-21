package io.github.naimelijah.hrsched.access.adapter.in.web;

/**
 * The body of a request to give an account a new temporary password.
 *
 * <p>{@link #toString()} never shows it.
 *
 * @param temporaryPassword the password the account is given, which its owner must replace
 */
public record ResetPasswordRequest(String temporaryPassword) {

  /**
   * @return the password given
   * @throws MalformedRequestException if it is absent, which Jackson maps to null rather than
   *     rejecting
   */
  String requiredTemporaryPassword() {
    if (temporaryPassword == null) {
      throw new MalformedRequestException("temporaryPassword is required");
    }
    return temporaryPassword;
  }

  @Override
  public String toString() {
    return "ResetPasswordRequest[redacted]";
  }
}
