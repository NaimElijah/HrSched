package io.github.naimelijah.hrsched.access.adapter.in.web;

/**
 * @param name what the organisation is called
 */
public record OnboardOrganisationRequest(String name) {
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
