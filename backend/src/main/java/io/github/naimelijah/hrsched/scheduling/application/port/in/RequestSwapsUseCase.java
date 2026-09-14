package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/**
 * Driving port: asking for one of your shifts to be covered by a colleague (requirement F7).
 *
 * <p>Q11 settled who answers: a <strong>Scheduler</strong> approves, and the colleague is informed
 * rather than asked. So a swap is a request to a Scheduler that happens to name a colleague, not a
 * negotiation between two employees, and the counterparty gets no veto here.
 *
 * <p>Approving does not edit the published roster. It cannot: R6 says a published roster is
 * superseded rather than changed, because people arrange their lives around it. Approval builds the
 * roster that <em>would</em> have been published had the swap been in it, checks it against the
 * organisation's rules like any other, and publishes it — which supersedes the old one through
 * exactly the code path every other publication uses. A swap that would break a rule is refused
 * with the violations listed, and there is no override, for the same reason there is none anywhere
 * else.
 */
public interface RequestSwapsUseCase {

  /**
   * @param actor whoever is asking; the requester themself, or an Admin or Scheduler on their
   *     behalf
   * @param command which roster, which shift, and who is being asked to take it
   * @return the new request's identity
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException if that
   *     organisation has no such roster
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException if the
   *     roster is not the one currently published
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssignmentException if the
   *     requester does not actually cover that shift
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.SwapWithThemselfException if the
   *     two parties are the same person
   *     <p>Whether the colleague may actually cover the shift is <strong>not</strong> decided here.
   *     Answering that means reading the workforce directory, which an Employee may not do because
   *     listing colleagues discloses their unavailability. Asking is not deciding: {@link
   *     #approveSwap} checks eligibility, and a Scheduler has the access to do it.
   */
  SwapRequestId requestSwap(Actor actor, RequestSwapCommand command);

  /**
   * @param actor whoever is asking
   * @param organisation whose swap requests
   * @return every request an Admin or Scheduler holds; for anybody else, only the ones they are
   *     party to — a swap request names two colleagues and what one of them wants off their plate
   */
  List<PublishedSwapRequest> findSwapRequests(Actor actor, OrganisationId organisation);

  /**
   * @param actor whoever is asking
   * @param organisation whose swap request
   * @param request which one
   * @return it, exactly when {@link #findSwapRequests} would list it for this actor. Otherwise
   *     empty, so a request somebody is not party to reads the same as one that does not exist
   */
  Optional<PublishedSwapRequest> findSwapRequest(
      Actor actor, OrganisationId organisation, SwapRequestId request);

  /**
   * Agrees to a swap, and publishes the roster that carries it.
   *
   * @param actor whoever is approving; must be a Scheduler
   * @param organisation whose swap
   * @param request which one
   * @return the newly published roster, which supersedes the one the request was raised against
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException if it has
   *     already been decided
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeNotQualifiedException if
   *     the colleague does not hold what the shift demands
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException if the
   *     resulting roster would break any working-time rule. There is no override
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.OverlappingAssignmentException if
   *     the colleague already works a shift on that roster overlapping the one handed over
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterPublicationConflictException
   *     if a roster overlapping the replacement was published at the same time
   */
  Roster approveSwap(Actor actor, OrganisationId organisation, SwapRequestId request);

  /**
   * Declines a swap. Nothing about any roster changes.
   *
   * @param actor whoever is rejecting; must be a Scheduler
   * @param organisation whose swap
   * @param request which one
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException if it has
   *     already been decided
   */
  void rejectSwap(Actor actor, OrganisationId organisation, SwapRequestId request);
}
