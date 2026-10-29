package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.Notifier;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;

/**
 * A notifier whose every telling fails, as storing a notification can.
 *
 * <p>Telling somebody is a write, not a message sent: the notifications use case stores a row,
 * inside the scheduling use case's transaction (ADR-023), and that write can still fail. So a use
 * case has to tell before it records, or an audit line outlives the rollback the failure causes.
 *
 * <p>Shared between SchedulingServiceTest and SwapServiceTest, as {@link RecordingNotifier} is.
 */
final class FailingNotifier implements Notifier {

  /** What every telling throws, so a test can tell it apart from any refusal of the use case. */
  static final class TellingFailed extends RuntimeException {
    TellingFailed() {
      super("Invented: the notification could not be stored");
    }
  }

  @Override
  public void swapRequested(
      Actor actor, OrganisationId organisation, EmployeeId counterparty, SwapRequestId request) {
    throw new TellingFailed();
  }

  @Override
  public void swapApproved(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
    throw new TellingFailed();
  }

  @Override
  public void swapRejected(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
    throw new TellingFailed();
  }

  @Override
  public void swapLapsed(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
    throw new TellingFailed();
  }
}
