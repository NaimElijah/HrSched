package io.github.naimelijah.hrsched.scheduling.adapter.out.notification;

import io.github.naimelijah.hrsched.notifications.application.port.in.NotifiableEvent;
import io.github.naimelijah.hrsched.notifications.application.port.in.NotifyCommand;
import io.github.naimelijah.hrsched.notifications.application.port.in.NotifyUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.out.Notifier;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import org.springframework.stereotype.Component;

/**
 * Anti-corruption layer between scheduling and notifications.
 *
 * <p>ONE OF TWO CLASSES IN SCHEDULING PERMITTED TO NAME ANOTHER CONTEXT, the other being {@code
 * WorkforceDirectoryAdapter}, and like it this touches only a published driving port (ADR-008).
 * Nothing inward of the {@link Notifier} port knows that a {@code notifications} context exists, so
 * replacing an in-app inbox with email is a change to this file and nothing else.
 *
 * <p>The translation it performs is the whole point: scheduling says "a swap was approved", and
 * this decides that the other context's word for that is {@link NotifiableEvent#SWAP_APPROVED}. Two
 * vocabularies that happen to agree today are still two vocabularies.
 */
@Component
public class NotificationAdapter implements Notifier {

  private final NotifyUseCase notifications;

  public NotificationAdapter(NotifyUseCase notifications) {
    this.notifications = notifications;
  }

  @Override
  public void swapRequested(
      Actor actor, OrganisationId organisation, EmployeeId counterparty, SwapRequestId request) {
    tell(actor, organisation, counterparty, NotifiableEvent.SWAP_REQUESTED, request);
  }

  @Override
  public void swapApproved(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
    tell(actor, organisation, party, NotifiableEvent.SWAP_APPROVED, request);
  }

  @Override
  public void swapRejected(
      Actor actor, OrganisationId organisation, EmployeeId party, SwapRequestId request) {
    tell(actor, organisation, party, NotifiableEvent.SWAP_REJECTED, request);
  }

  private void tell(
      Actor actor,
      OrganisationId organisation,
      EmployeeId recipient,
      NotifiableEvent kind,
      SwapRequestId request) {
    notifications.notify(actor, new NotifyCommand(organisation, recipient, kind, request.value()));
  }
}
