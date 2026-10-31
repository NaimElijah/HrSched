package io.github.naimelijah.hrsched.access.adapter.in.web;

/**
 * The body of a request to disable an account or restore one.
 *
 * <p>A boxed {@code Boolean} rather than a primitive, so an absent field is refused rather than
 * read as {@code false}. A primitive would make a request that merely forgot the field disable
 * somebody, quietly, which is an outcome nobody would attribute to a typo.
 *
 * @param active whether the account may sign in and act
 */
public record SetActiveRequest(Boolean active) {

  /**
   * @return whether the account may act
   * @throws MalformedRequestException if the field is absent, which Jackson maps to null rather
   *     than rejecting
   */
  boolean requiredActive() {
    if (active == null) {
      throw new MalformedRequestException("active is required");
    }
    return active;
  }
}
