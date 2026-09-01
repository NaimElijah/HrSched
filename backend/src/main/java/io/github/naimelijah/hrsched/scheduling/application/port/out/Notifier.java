package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;

/**
 * Driven port: telling somebody that a swap they are party to has moved.
 *
 * <p>Exists because Q11 says the colleague is <em>informed</em>. Scheduling states who needs
 * telling and about what; how that reaches a person is somebody else's problem, and is answered
 * today by an adapter over the {@code notifications} context (ADR-021).
 *
 * <p>Three methods rather than one taking a kind, so that adding a fourth thing worth telling
 * somebody about is a compile error at every implementation rather than a silently unhandled enum
 * value.
 *
 * <p>Carries no name and no message: every parameter is an identifier, and what a person reads is
 * assembled where it is read.
 */
public interface Notifier {

  /**
   * @param actor on whose behalf the notification is raised
   * @param organisation whose swap
   * @param counterparty the colleague being asked to take the shift
   * @param request which swap request
   */
  void swapRequested(
      Actor actor, OrganisationId organisation, EmployeeId counterparty, SwapRequestId request);

  /**
   * @param actor on whose behalf the notification is raised
   * @param organisation whose swap
   * @param party somebody the decision affects
   * @param request which swap request
   */
  void swapApproved(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request);

  /**
   * @param actor on whose behalf the notification is raised
   * @param organisation whose swap
   * @param party somebody the decision affects
   * @param request which swap request
   */
  void swapRejected(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request);
}
