package io.github.naimelijah.hrsched.access.adapter.in.web;

/**
 * The body of a password change.
 *
 * <p>Absent fields become empty strings rather than a rejection, so that a missing current password
 * is refused as a wrong one and a missing new password is refused by the policy - each in the place
 * that already has something sensible to say about it.
 *
 * <p>{@link #toString()} reveals neither.
 *
 * @param currentPassword the password in use, or null if the field was absent
 * @param newPassword the password wanted instead, or null if the field was absent
 */
public record ChangePasswordRequest(String currentPassword, String newPassword) {

  String submittedCurrent() {
    return currentPassword == null ? "" : currentPassword;
  }

  String submittedNew() {
    return newPassword == null ? "" : newPassword;
  }

  @Override
  public String toString() {
    return "ChangePasswordRequest[redacted]";
  }
}
