package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hand-written, like every other double here: the directory is our own port.
 *
 * <p>It honours the tenant check the real adapter honours, because several tests turn on the actor
 * being carried through to it rather than dropped.
 */
final class FakeWorkforce implements WorkforceDirectory {

  private final Map<OrganisationId, List<RosterCandidate>> people = new HashMap<>();
  private final Map<OrganisationId, Set<QualificationId>> defined = new HashMap<>();

  void add(OrganisationId organisation, RosterCandidate candidate) {
    people.computeIfAbsent(organisation, key -> new ArrayList<>()).add(candidate);
  }

  /**
   * Somebody declaring, after whatever was decided about them so far, that they cannot work a span.
   *
   * @throws IllegalArgumentException if this organisation has nobody by that identity
   */
  void declareUnavailable(OrganisationId organisation, EmployeeId employee, TimeRange period) {
    List<RosterCandidate> theirs = people.getOrDefault(organisation, new ArrayList<>());
    int at = -1;
    for (int i = 0; i < theirs.size(); i++) {
      if (theirs.get(i).id().equals(employee)) {
        at = i;
      }
    }
    if (at < 0) {
      throw new IllegalArgumentException("Nobody by that identity to declare anything");
    }
    RosterCandidate before = theirs.get(at);
    List<TimeRange> unavailability = new ArrayList<>(before.unavailability());
    unavailability.add(period);
    theirs.set(
        at,
        new RosterCandidate(
            before.id(), before.qualifications(), unavailability, before.preferences()));
  }

  void define(OrganisationId organisation, QualificationId qualification) {
    defined.computeIfAbsent(organisation, key -> new LinkedHashSet<>()).add(qualification);
  }

  @Override
  public List<RosterCandidate> candidatesFor(Actor actor, OrganisationId organisation) {
    actor.requireAccessTo(organisation);
    return people.getOrDefault(organisation, List.of());
  }

  @Override
  public Set<QualificationId> definedQualifications(Actor actor, OrganisationId organisation) {
    actor.requireAccessTo(organisation);
    return defined.getOrDefault(organisation, Set.of());
  }
}
