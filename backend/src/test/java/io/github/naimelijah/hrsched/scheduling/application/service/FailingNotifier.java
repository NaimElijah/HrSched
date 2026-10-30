package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.Notifier;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;

/**
 * A notifier whose tellings fail from a chosen one on, as storing a notification can.
 *
 * <p>Telling somebody is a write, not a message sent: the notifications use case stores a row,
 * inside the scheduling use case's transaction (ADR-023), and that write can still fail. So a use
 * case has to tell before it records, or an audit line outlives the rollback the failure causes.
 * Failing a later telling, not only the first, is what shows a use case records after every telling
 * rather than after the first.
 *
 * <p>Shared between SchedulingServiceTest and SwapServiceTest, as {@link RecordingNotifier} is.
 */
final class FailingNotifier implements Notifier {

  /**
   * What a failing telling throws, so a test can tell it apart from any refusal of the use case.
   */
  static final class TellingFailed extends RuntimeException {
    TellingFailed() {
      super("Invented: the notification could not be stored");
    }
  }

  private final int firstFailing;
  private int tellings;

  /** Fails every telling. */
  FailingNotifier() {
    this(1);
  }

  /**
   * @param firstFailing which telling, counted from one, fails first. Every one before it is
   *     accepted and kept nowhere, and every one after it fails too
   * @throws IllegalArgumentException if it is not positive
   */
  FailingNotifier(int firstFailing) {
    if (firstFailing < 1) {
      throw new IllegalArgumentException("Tellings are counted from one, not " + firstFailing);
    }
    this.firstFailing = firstFailing;
  }

  private void tell() {
    if (++tellings >= firstFailing) {
      throw new TellingFailed();
    }
  }

  @Override
  public void swapRequested(
      Actor actor, OrganisationId organisation, EmployeeId counterparty, SwapRequestId request) {
    tell();
  }

  @Override
  public void swapApproved(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
    tell();
  }

  @Override
  public void swapRejected(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
    tell();
  }

  @Override
  public void swapLapsed(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
    tell();
  }
}
