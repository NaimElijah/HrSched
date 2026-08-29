package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.List;

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
   * @return every qualification defined by that organisation
   */
  List<PublishedQualification> findQualifications(Actor actor, OrganisationId organisation);
}
