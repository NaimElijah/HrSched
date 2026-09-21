package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;

/**
 * Driven port: whether a personnel record exists in an organisation.
 *
 * <p>The one thing access needs to know about the workforce, and deliberately the only thing. An
 * account's employee link is what makes self-service work (ADR-012), so it has to name somebody who
 * is really there; nothing else about that person is any of this context's business, and a port
 * that answered with a name would put one in reach of a log that must never hold one.
 *
 * <p>Implemented by an adapter, because the answer comes from another bounded context and only an
 * adapter may name one (ADR-008).
 */
public interface EmployeeDirectory {

  /**
   * @param actor whoever is asking, whose own access to the organisation is checked by the context
   *     answering
   * @param organisation the organisation the record would belong to
   * @param employee the personnel record being named
   * @return whether that organisation holds that employee
   */
  boolean isEmployedHere(Actor actor, OrganisationId organisation, EmployeeId employee);
}
