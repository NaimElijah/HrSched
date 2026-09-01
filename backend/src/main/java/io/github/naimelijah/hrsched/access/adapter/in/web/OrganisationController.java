package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.access.application.port.in.ManageOrganisationsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.OnboardOrganisationCommand;
import io.github.naimelijah.hrsched.access.application.port.in.PublishedOrganisation;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
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
 * Driving adapter exposing organisation administration over HTTP (requirement F13).
 *
 * <p>{@code X-Organisation} is optional here, and only here. Everywhere else it names the tenant
 * being acted on and is always required; on this controller the operator acts on the register of
 * tenants rather than inside one, and onboarding the first organisation would otherwise require an
 * organisation to already exist. That is also why ADR-020's boundary check exempts this path.
 *
 * <p>Reading one organisation is the only place in the HTTP API where a tenant actor can reach past
 * their own boundary and be caught doing it: the identity comes from {@code X-Organisation} and the
 * target from the path, so unlike every other endpoint the two can genuinely differ.
 */
@RestController
@RequestMapping("/api/organisations")
public class OrganisationController {

  private final ManageOrganisationsUseCase manageOrganisations;

  public OrganisationController(ManageOrganisationsUseCase manageOrganisations) {
    this.manageOrganisations = manageOrganisations;
  }

  @PostMapping
  public ResponseEntity<OrganisationResponse> onboard(
      @RequestHeader(value = "X-Organisation", required = false) String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @RequestBody OnboardOrganisationRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    OrganisationId id =
        manageOrganisations.onboardOrganisation(
            actor, new OnboardOrganisationCommand(request.requiredName()));
    return ResponseEntity.created(URI.create("/api/organisations/" + id.value()))
        .body(new OrganisationResponse(id.value(), request.requiredName()));
  }

  @GetMapping
  public List<OrganisationResponse> list(
      @RequestHeader(value = "X-Organisation", required = false) String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return manageOrganisations.findOrganisations(actor).stream()
        .map(OrganisationController::respond)
        .toList();
  }

  @GetMapping("/{id}")
  public ResponseEntity<OrganisationResponse> read(
      @RequestHeader(value = "X-Organisation", required = false) String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return manageOrganisations
        .findOrganisation(actor, new OrganisationId(id))
        .map(OrganisationController::respond)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static OrganisationResponse respond(PublishedOrganisation organisation) {
    return new OrganisationResponse(organisation.id().value(), organisation.name());
  }
}
