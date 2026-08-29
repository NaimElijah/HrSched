package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the acting identity from request headers.
 *
 * <p>PLACEHOLDER, AND INSECURE BY DESIGN. Any caller can claim any role in any organisation — or
 * claim to be the platform operator — simply by setting a header. This exists only so the use cases
 * can be exercised before an identity provider is chosen, and it must be deleted the moment one is.
 * It is not a stepping stone towards a real implementation.
 *
 * <p>{@code X-Organisation} names the organisation being acted <em>on</em>, and is always required.
 * A tenant actor may only name their own; an operator may name any, which is what makes {@link
 * Operator} reachable at all rather than a type only unit tests can construct.
 *
 * <p>Deliberately duplicated in each web adapter rather than shared: it is adapter plumbing, and
 * sharing it would either put Spring in the kernel or make one context depend on the plumbing of
 * another.
 */
final class ActorFromHeaders {

  /**
   * Claimed in {@code X-Role} to act as the platform operator, which is not a tenant {@link Role}.
   */
  private static final String OPERATOR = "OPERATOR";

  private ActorFromHeaders() {}

  /**
   * @param organisation the organisation being acted on
   * @param user the claimed user identity
   * @param role the claimed role, or {@code OPERATOR} for a platform operator
   * @param employee the personnel record the user is, absent if they are not rostered
   * @return the actor the caller claims to be
   * @throws MalformedRequestException if any header is missing or unparseable
   */
  static Actor resolve(String organisation, String user, String role, String employee) {
    UserId userId = new UserId(uuid(user, "X-User"));
    if (OPERATOR.equalsIgnoreCase(trimmed(role))) {
      return new Operator(userId);
    }
    return new TenantActor(
        userId,
        organisationOf(organisation),
        roleOf(role),
        Optional.ofNullable(trimmed(employee))
            .map(value -> new EmployeeId(uuid(value, "X-Employee"))));
  }

  /**
   * @param header the {@code X-Organisation} value
   * @return the organisation being acted on
   * @throws MalformedRequestException if it is missing or not a UUID
   */
  static OrganisationId organisationOf(String header) {
    return new OrganisationId(uuid(header, "X-Organisation"));
  }

  private static UUID uuid(String value, String header) {
    String present = trimmed(value);
    if (present == null) {
      throw new MalformedRequestException(header + " is required");
    }
    try {
      return UUID.fromString(present);
    } catch (IllegalArgumentException malformed) {
      throw new MalformedRequestException(header + " must be a UUID");
    }
  }

  private static Role roleOf(String role) {
    String present = trimmed(role);
    if (present == null) {
      throw new MalformedRequestException("X-Role is required");
    }
    try {
      return Role.valueOf(present.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new MalformedRequestException(
          "X-Role must be one of %s or %s".formatted(Arrays.toString(Role.values()), OPERATOR));
    }
  }

  private static String trimmed(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
