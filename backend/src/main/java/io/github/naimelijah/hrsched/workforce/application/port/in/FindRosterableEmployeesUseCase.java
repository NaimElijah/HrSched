package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;

/**
 * Workforce's published API: who an organisation could roster.
 *
 * <p>Not the only one of this package's ports another context uses, each from an {@code
 * adapter.out.workforce} class: scheduling also reads qualifications through {@code
 * ManageQualificationsUseCase}, and access asks {@code ManageEmployeesUseCase.findEmployee} whether
 * an employee exists. Another context may reach this package and nothing else (ADR-008): this
 * context's aggregate, its services and its storage stay private.
 */
public interface FindRosterableEmployeesUseCase {

  /**
   * Lists everybody an organisation could roster. Their qualifications and absences decide where
   * they may be put; their preferences only weigh one placement against another, and never block
   * one. Their contracted hours are carried but not read by scheduling yet (#103).
   *
   * <p>Restricted to an Admin or a Scheduler: the answer carries every employee's absences, which
   * are personal data, and an employee has no reason to read a colleague's. Any other role is
   * refused with a {@code NotPermittedException}, and the platform Operator, which belongs to no
   * organisation, with a {@code NotATenantMemberException}.
   *
   * @param actor whoever is asking; must be an Admin or Scheduler of the organisation
   * @param organisation whose employees to list
   * @return every employee of that organisation, as published data, ordered by name, then by
   *     identity; empty if it has none
   * @throws CrossTenantAccessException if the actor may not see that organisation
   */
  List<RosterableEmployee> findRosterable(Actor actor, OrganisationId organisation);
}
