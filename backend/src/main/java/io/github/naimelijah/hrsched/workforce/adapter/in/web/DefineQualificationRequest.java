package io.github.naimelijah.hrsched.workforce.adapter.in.web;

/**
 * @param name what the qualification is called
 */
public record DefineQualificationRequest(String name) {
  /**
   * @return the name given
   * @throws MalformedRequestException if the field is absent, which Jackson maps to null rather
   *     than rejecting
   */
  String requiredName() {
    if (name == null) {
      throw new MalformedRequestException("name is required");
    }
    return name;
  }
}
