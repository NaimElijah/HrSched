package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.application.port.in.RequestSwapCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RequestSwapsUseCase;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownSwapRequestException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Driving adapter exposing swap requests over HTTP (requirement F7).
 *
 * <p>Its own controller rather than more of {@code SchedulingController}, matching the split
 * between {@code SwapService} and {@code SchedulingService}: a swap is a workflow with its own
 * audience.
 *
 * <p>Approving returns the roster it published, not the request, because that is the thing the
 * caller now has to look at — the old one has been superseded.
 */
@RestController
@RequestMapping("/api/swaps")
public class SwapController {

  private final RequestSwapsUseCase requestSwaps;

  /**
   * @param requestSwaps the use case every route of this controller delegates to
   */
  public SwapController(RequestSwapsUseCase requestSwaps) {
    this.requestSwaps = requestSwaps;
  }

  /**
   * Asks for one of a published roster's assignments to be handed to a colleague. The requester
   * themself may, and an Admin or a Scheduler on their behalf. Answers 201 with the new request and
   * its location. Whether the colleague may cover the shift is decided at approval, not here.
   *
   * @param request the roster, the shift and the two people; a member left out is answered as a 400
   *     problem detail
   */
  @PostMapping
  public ResponseEntity<SwapRequestResponse> request(
      @AuthenticationPrincipal Actor actor, @RequestBody RequestSwapRequest request) {
    SwapRequestId id =
        requestSwaps.requestSwap(
            actor,
            new RequestSwapCommand(
                actingIn(actor),
                request.requiredRoster(),
                request.requiredShift(),
                request.requiredRequester(),
                request.requiredCounterparty()));
    return ResponseEntity.created(URI.create("/api/swaps/" + id.value()))
        .body(
            new SwapRequestResponse(
                id.value(),
                request.requiredRoster().value(),
                request.requiredShift().value(),
                request.requiredRequester().value(),
                request.requiredCounterparty().value(),
                SwapRequestStatus.REQUESTED.name()));
  }

  /**
   * Lists the swap requests the caller may see: every one for an Admin or a Scheduler, otherwise
   * only those the caller is party to, oldest first.
   */
  @GetMapping
  public List<SwapRequestResponse> list(@AuthenticationPrincipal Actor actor) {
    return requestSwaps.findSwapRequests(actor, actingIn(actor)).stream()
        .map(SwapRequestResponse::from)
        .toList();
  }

  /**
   * Reads one swap request, exactly when the listing would show it to the caller.
   *
   * @param id the swap request to read
   * @return it
   * @throws UnknownSwapRequestException answered as a 404 problem detail both when there is no such
   *     request and when the caller is not party to it, so a response cannot tell the two apart
   */
  @GetMapping("/{id}")
  public SwapRequestResponse find(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    SwapRequestId request = new SwapRequestId(id);
    return requestSwaps
        .findSwapRequest(actor, actingIn(actor), request)
        .map(SwapRequestResponse::from)
        .orElseThrow(() -> new UnknownSwapRequestException(request));
  }

  /**
   * Approves a request by publishing the replacement roster that carries it. Only a Scheduler may.
   * A replacement that would break a rule is answered as a 422 problem detail, and there is no
   * override. A colleague who already covers the shift is answered as a 409 problem detail of type
   * {@code already-assigned} instead, and nothing is published.
   *
   * @param id the request
   * @return the published replacement
   */
  @PostMapping("/{id}/approval")
  public RosterResponse approve(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return RosterResponse.from(
        requestSwaps.approveSwap(actor, actingIn(actor), new SwapRequestId(id)));
  }

  /**
   * Declines a request; no roster changes. Only a Scheduler may. Answers 204.
   *
   * @param id the request
   */
  @PostMapping("/{id}/rejection")
  public ResponseEntity<Void> reject(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    requestSwaps.rejectSwap(actor, actingIn(actor), new SwapRequestId(id));
    return ResponseEntity.noContent().build();
  }

  /**
   * The organisation this request acts in, taken from the acting account and from nowhere else.
   *
   * <p>It replaced {@code X-Organisation}, and the difference is the whole point: there is no
   * longer anything in the request naming an organisation, so there is nothing for a caller to
   * change. The platform operator is refused here, because it belongs to none (ADR-030).
   *
   * @param actor whoever the token named
   * @return the organisation they belong to
   */
  private static OrganisationId actingIn(Actor actor) {
    return actor.requireTenantMembership().organisation();
  }
}
