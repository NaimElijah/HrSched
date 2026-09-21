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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Driving adapter exposing organisation administration over HTTP (requirement F13).
 *
 * <p><strong>The first controller to take its identity from the access token.</strong> Who is
 * acting is the account the token names, resolved against stored state on every request, rather
 * than four headers any caller could write. Which of these routes each kind of account may reach is
 * stated twice on purpose: in the security chain's URL rules, and in the use case's own guard. The
 * rules are the coarse outer line; the guard is the one that decides.
 *
 * <p>Reading one organisation is the place in the HTTP API where the tenant guard genuinely fires:
 * the identity comes from the token and the target from the path, so unlike an endpoint that takes
 * both from the same place, the two can differ — and a tenant naming somebody else's organisation
 * gets the ordinary 404.
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
      @AuthenticationPrincipal Actor actor, @RequestBody OnboardOrganisationRequest request) {
    OrganisationId id =
        manageOrganisations.onboardOrganisation(
            actor,
            new OnboardOrganisationCommand(
                request.requiredName(),
                request.requiredFirstAdminSignInName(),
                request.requiredFirstAdminTemporaryPassword()));
    return ResponseEntity.created(URI.create("/api/organisations/" + id.value()))
        .body(new OrganisationResponse(id.value(), request.requiredName()));
  }

  @GetMapping
  public List<OrganisationResponse> list(@AuthenticationPrincipal Actor actor) {
    return manageOrganisations.findOrganisations(actor).stream()
        .map(OrganisationController::respond)
        .toList();
  }

  @GetMapping("/{id}")
  public ResponseEntity<OrganisationResponse> read(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
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
