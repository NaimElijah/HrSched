package io.github.naimelijah.hrsched.scheduling.adapter.out.workforce;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.workforce.application.port.in.FindRosterableEmployeesUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.RosterableEmployee;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Anti-corruption layer between scheduling and workforce.
 *
 * <p>THE ONLY CLASS IN SCHEDULING PERMITTED TO NAME ANOTHER CONTEXT, and it may name only the
 * published driving port, never the workforce domain. The architecture rules enforce that; this
 * adapter is where the allowance is spent, deliberately, in one place.
 *
 * <p>Its whole job is translation. Scheduling asks its own question through its own port and gets
 * back its own type; if workforce reshapes its aggregate, this file changes and nothing else does.
 */
@Component
public class WorkforceDirectoryAdapter implements WorkforceDirectory {

  private final FindRosterableEmployeesUseCase workforce;

  public WorkforceDirectoryAdapter(FindRosterableEmployeesUseCase workforce) {
    this.workforce = workforce;
  }

  @Override
  public List<RosterCandidate> candidatesFor(Actor actor, OrganisationId organisation) {
    return workforce.findRosterable(actor, organisation).stream()
        .map(WorkforceDirectoryAdapter::translate)
        .toList();
  }

  private static RosterCandidate translate(RosterableEmployee employee) {
    return new RosterCandidate(
        employee.id(), employee.name(), employee.qualifications(), employee.unavailability());
  }
}
