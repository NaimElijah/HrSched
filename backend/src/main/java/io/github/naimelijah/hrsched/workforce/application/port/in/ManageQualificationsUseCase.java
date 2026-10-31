package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.List;
import java.util.Optional;

/** Driving port: defining the qualifications that exist within an organisation. */
public interface ManageQualificationsUseCase {

  /**
   * Defines a qualification an employee of the organisation can then be granted.
   *
   * <p>R23 requires the name to be unique within the organisation, and #95 builds that; until then,
   * two qualifications may share one. A blank name is refused with an {@code
   * InvalidQualificationNameException}; any role but an Admin, with a {@code
   * NotPermittedException}; an actor of another organisation, with a {@code
   * CrossTenantAccessException}; and the platform Operator, which belongs to none, with a {@code
   * NotATenantMemberException}.
   *
   * @param actor whoever is defining it; must be an Admin of the organisation
   * @param command which organisation, and what the qualification is called
   * @return the new qualification's identity
   */
  QualificationId defineQualification(Actor actor, DefineQualificationCommand command);

  /**
   * Lists the qualifications an organisation defines, to any member of it.
   *
   * <p>An actor of another organisation is refused with a {@code CrossTenantAccessException}, and
   * the platform Operator with a {@code NotATenantMemberException}.
   *
   * @param actor whoever is asking; any member of the organisation
   * @param organisation whose qualifications to list
   * @return every qualification defined by that organisation, ordered by name, then by identity;
   *     empty if it defines none
   */
  List<PublishedQualification> findQualifications(Actor actor, OrganisationId organisation);

  /**
   * Reads one qualification, to any member of the organisation. Refused as {@link
   * #findQualifications} is.
   *
   * @param actor whoever is asking; any member of the organisation, as for the listing
   * @param organisation the organisation to look in
   * @param qualification which one
   * @return it, or empty if that organisation defines none by that identity
   */
  Optional<PublishedQualification> findQualification(
      Actor actor, OrganisationId organisation, QualificationId qualification);
}
