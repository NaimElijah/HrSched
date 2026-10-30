package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.List;
import java.util.Optional;

/** Driving port: defining the qualifications that exist within an organisation. */
public interface ManageQualificationsUseCase {

  /**
   * @param actor whoever is defining it; must be an Admin of the organisation
   * @param command which organisation, and what the qualification is called
   * @return the new qualification's identity
   */
  QualificationId defineQualification(Actor actor, DefineQualificationCommand command);

  /**
   * @param actor whoever is asking
   * @param organisation whose qualifications to list
   * @return every qualification defined by that organisation, ordered by name, then by identity
   */
  List<PublishedQualification> findQualifications(Actor actor, OrganisationId organisation);

  /**
   * @param actor whoever is asking; any member of the organisation, as for the listing
   * @param organisation the organisation to look in
   * @param qualification which one
   * @return it, or empty if that organisation defines none by that identity
   */
  Optional<PublishedQualification> findQualification(
      Actor actor, OrganisationId organisation, QualificationId qualification);
}
