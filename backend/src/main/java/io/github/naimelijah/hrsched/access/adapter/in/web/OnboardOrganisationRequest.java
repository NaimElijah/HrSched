package io.github.naimelijah.hrsched.access.adapter.in.web;

/**
 * The body of an onboarding request.
 *
 * <p>The first Admin is required, because an organisation nobody can administer is not usable and
 * the operator cannot act inside one to add somebody later (ADR-030).
 *
 * <p>{@link #toString()} reveals nothing of the admin: the password is a credential and the sign-in
 * name identifies a person.
 *
 * @param name what the organisation is called
 * @param firstAdmin who will administer it
 */
public record OnboardOrganisationRequest(String name, FirstAdmin firstAdmin) {

  /**
   * @param signInName the name the first Admin signs in with
   * @param temporaryPassword the password they must change at first sign-in
   */
  public record FirstAdmin(String signInName, String temporaryPassword) {

    @Override
    public String toString() {
      return "FirstAdmin[redacted]";
    }
  }

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

  /**
   * @return the first Admin's sign-in name
   * @throws MalformedRequestException if the admin or the field is absent
   */
  String requiredFirstAdminSignInName() {
    if (firstAdmin == null || firstAdmin.signInName() == null) {
      throw new MalformedRequestException("firstAdmin.signInName is required");
    }
    return firstAdmin.signInName();
  }

  /**
   * @return the first Admin's temporary password
   * @throws MalformedRequestException if the admin or the field is absent
   */
  String requiredFirstAdminTemporaryPassword() {
    if (firstAdmin == null || firstAdmin.temporaryPassword() == null) {
      throw new MalformedRequestException("firstAdmin.temporaryPassword is required");
    }
    return firstAdmin.temporaryPassword();
  }

  @Override
  public String toString() {
    return "OnboardOrganisationRequest[name=%s, firstAdmin=redacted]".formatted(name);
  }
}
