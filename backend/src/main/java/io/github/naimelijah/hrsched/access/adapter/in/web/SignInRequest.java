package io.github.naimelijah.hrsched.access.adapter.in.web;

/**
 * The body of a sign-in request.
 *
 * <p>Neither field is checked for shape. A name this system could never hold has to be refused in
 * exactly the way a name nobody holds is refused, so validating here - where failure would be a 400
 * rather than a 401 - would answer, for free, which names are possible.
 *
 * <p>An absent field becomes an empty string rather than a rejection, for the same reason.
 *
 * <p>{@link #toString()} reveals neither. This record is built from whatever arrived, and whatever
 * arrived includes a password.
 *
 * @param signInName the name submitted, or null if the field was absent
 * @param password the password submitted, or null if the field was absent
 */
public record SignInRequest(String signInName, String password) {

  /**
   * @return the name submitted, or an empty string if none was
   */
  public String submittedName() {
    return signInName == null ? "" : signInName;
  }

  /**
   * @return the password submitted, or an empty string if none was
   */
  public String submittedPassword() {
    return password == null ? "" : password;
  }

  @Override
  public String toString() {
    return "SignInRequest[redacted]";
  }
}
