package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.application.port.in.RequestSwapCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RequestSwapsUseCase;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

  public SwapController(RequestSwapsUseCase requestSwaps) {
    this.requestSwaps = requestSwaps;
  }

  @PostMapping
  public ResponseEntity<SwapRequestResponse> request(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @RequestBody RequestSwapRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    SwapRequestId id =
        requestSwaps.requestSwap(
            actor,
            new RequestSwapCommand(
                ActorFromHeaders.organisationOf(organisation),
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

  @GetMapping
  public List<SwapRequestResponse> list(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return requestSwaps
        .findSwapRequests(actor, ActorFromHeaders.organisationOf(organisation))
        .stream()
        .map(SwapRequestResponse::from)
        .toList();
  }

  @GetMapping("/{id}")
  public ResponseEntity<SwapRequestResponse> find(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return requestSwaps
        .findSwapRequest(
            actor, ActorFromHeaders.organisationOf(organisation), new SwapRequestId(id))
        .map(SwapRequestResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/{id}/approval")
  public RosterResponse approve(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return RosterResponse.from(
        requestSwaps.approveSwap(
            actor, ActorFromHeaders.organisationOf(organisation), new SwapRequestId(id)));
  }

  @PostMapping("/{id}/rejection")
  public ResponseEntity<Void> reject(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    requestSwaps.rejectSwap(
        actor, ActorFromHeaders.organisationOf(organisation), new SwapRequestId(id));
    return ResponseEntity.noContent().build();
  }
}
