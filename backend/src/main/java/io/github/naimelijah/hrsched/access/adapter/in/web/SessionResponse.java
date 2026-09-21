package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.access.application.port.in.AccountKind;
import io.github.naimelijah.hrsched.access.application.port.in.PublishedSession;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import java.util.UUID;

/**
 * Who is signed in, as the API says it.
 *
 * <p>Nulls rather than {@code Optional} in the JSON: an absent organisation is what an operator
 * looks like, and a client reads that as a missing member either way.
 *
 * @param userId the account signed in
 * @param kind whether it belongs to an organisation or to the platform
 * @param organisation the organisation it belongs to, null for the operator
 * @param role what it may do there, null for the operator
 * @param employee the personnel record it is, null when it is nobody's
 * @param signInName the name it signed in with
 * @param passwordChangeRequired whether the password must be changed before anything else
 */
public record SessionResponse(
    UUID userId,
    AccountKind kind,
    UUID organisation,
    Role role,
    UUID employee,
    String signInName,
    boolean passwordChangeRequired) {

  /**
   * @param session what the use case published
   * @return the same thing, shaped for JSON
   */
  static SessionResponse of(PublishedSession session) {
    return new SessionResponse(
        session.userId(),
        session.kind(),
        session.organisation().orElse(null),
        session.role().orElse(null),
        session.employee().orElse(null),
        session.signInName(),
        session.passwordChangeRequired());
  }
}
