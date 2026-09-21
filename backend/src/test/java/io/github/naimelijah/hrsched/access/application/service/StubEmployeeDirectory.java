package io.github.naimelijah.hrsched.access.application.service;

import io.github.naimelijah.hrsched.access.application.port.out.EmployeeDirectory;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Answers whether an organisation employs somebody, which is the whole of the driven port.
 *
 * <p>Keeps the employees per organisation rather than in one set, because the rule being tested is
 * that a link may not reach into another organisation — a fake that ignored the organisation would
 * make that test pass whatever the service did.
 *
 * <p>Named without a {@code Test} suffix so Surefire does not try to run it.
 */
final class StubEmployeeDirectory implements EmployeeDirectory {

  private final Map<OrganisationId, Set<EmployeeId>> employed = new LinkedHashMap<>();

  /**
   * @param organisation who employs them
   * @return a fresh employee that organisation now has
   */
  EmployeeId employedBy(OrganisationId organisation) {
    EmployeeId employee = EmployeeId.newId();
    employed.computeIfAbsent(organisation, key -> new LinkedHashSet<>()).add(employee);
    return employee;
  }

  @Override
  public boolean isEmployedHere(Actor actor, OrganisationId organisation, EmployeeId employee) {
    return employed.getOrDefault(organisation, Set.of()).contains(employee);
  }
}
