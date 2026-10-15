package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.Notifier;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.ArrayList;
import java.util.List;

/**
 * Records who a scheduling use case said should be told what, so that "the colleague is informed"
 * is something a test can ask.
 *
 * <p>Shared between SchedulingServiceTest and SwapServiceTest, as {@link RecordingAuditTrail} is,
 * because a publication tells people things as well as a swap decision does.
 */
final class RecordingNotifier implements Notifier {

  /** One thing somebody was told: what happened, to whom, and about which request. */
  record Told(String what, EmployeeId who, SwapRequestId request) {}

  final List<Told> told = new ArrayList<>();

  @Override
  public void swapRequested(
      Actor actor, OrganisationId organisation, EmployeeId counterparty, SwapRequestId request) {
    told.add(new Told("REQUESTED", counterparty, request));
  }

  @Override
  public void swapApproved(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
    told.add(new Told("APPROVED", party, request));
  }

  @Override
  public void swapRejected(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
    told.add(new Told("REJECTED", party, request));
  }
}
