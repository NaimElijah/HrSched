package io.github.naimelijah.hrsched.scheduling.adapter.out.workforce;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.scheduling.domain.model.StatedPreferences;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.application.port.in.FindRosterableEmployeesUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageQualificationsUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.PublishedQualification;
import io.github.naimelijah.hrsched.workforce.application.port.in.RosterableEmployee;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Anti-corruption layer between scheduling and workforce.
 *
 * <p>ONE OF TWO CLASSES IN SCHEDULING THAT NAME ANOTHER CONTEXT, the other being {@code
 * NotificationAdapter}, and it may name only the published driving ports, never the workforce
 * domain. The architecture rules let only a class in a context's {@code adapter.out} name another
 * context, and only those ports (ADR-008); this adapter is where scheduling spends the allowance on
 * workforce, deliberately, in one place.
 *
 * <p>Its whole job is translation. Scheduling asks its own question through its own port and gets
 * back its own type; if workforce reshapes its aggregate, this file changes and nothing else does.
 */
@Component
public class WorkforceDirectoryAdapter implements WorkforceDirectory {

  private final FindRosterableEmployeesUseCase workforce;
  private final ManageQualificationsUseCase qualifications;

  public WorkforceDirectoryAdapter(
      FindRosterableEmployeesUseCase workforce, ManageQualificationsUseCase qualifications) {
    this.workforce = workforce;
    this.qualifications = qualifications;
  }

  @Override
  public List<RosterCandidate> candidatesFor(Actor actor, OrganisationId organisation) {
    return workforce.findRosterable(actor, organisation).stream()
        .map(WorkforceDirectoryAdapter::translate)
        .toList();
  }

  @Override
  public Set<QualificationId> definedQualifications(Actor actor, OrganisationId organisation) {
    return qualifications.findQualifications(actor, organisation).stream()
        .map(PublishedQualification::id)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static RosterCandidate translate(RosterableEmployee employee) {
    return new RosterCandidate(
        employee.id(),
        employee.name(),
        employee.qualifications(),
        employee.unavailability(),
        new StatedPreferences(employee.wouldLikeToWork(), employee.wouldRatherNotWork()));
  }
}
