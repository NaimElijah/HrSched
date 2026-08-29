package io.github.naimelijah.hrsched.workforce.adapter.in.web;

/**
 * @param name the employee name
 */
public record AddEmployeeRequest(String name) {
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
