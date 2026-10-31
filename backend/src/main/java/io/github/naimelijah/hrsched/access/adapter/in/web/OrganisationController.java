package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.access.application.port.in.ManageOrganisationsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.OnboardOrganisationCommand;
import io.github.naimelijah.hrsched.access.application.port.in.PublishedOrganisation;
import io.github.naimelijah.hrsched.access.domain.model.UnknownOrganisationException;
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
 * <p><strong>Who is acting is the account the access token names</strong>, resolved against stored
 * state on every request, never a header or a body. Which of these routes each kind of account may
 * reach is stated twice on purpose: in the security chain's URL rules, and in the use case's own
 * guard. The rules are the coarse outer line; the guard is the one that decides.
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

  /**
   * Onboards an organisation together with its first Admin; the platform operator's alone.
   *
   * @param actor whoever is onboarding it, taken from their verified token
   * @param request the organisation's name and its first Admin
   * @return 201 with a {@code Location} naming the new organisation, and its identity and name in
   *     the body. A refused password, a name nobody can hold, or a sign-in name already in use is
   *     answered by {@link AccessExceptionHandler}, and nothing is left behind
   */
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

  /**
   * Lists every organisation on the platform, by name and then by identity; the platform operator's
   * alone.
   *
   * @param actor whoever is asking, taken from their verified token
   * @return 200 with the register
   */
  @GetMapping
  public List<OrganisationResponse> list(@AuthenticationPrincipal Actor actor) {
    return manageOrganisations.findOrganisations(actor).stream()
        .map(OrganisationController::respond)
        .toList();
  }

  /**
   * Reads one organisation: any of them for the operator, only their own for anybody else.
   *
   * @param actor whoever is asking, taken from their verified token
   * @param id the organisation to read
   * @return it
   * @throws UnknownOrganisationException answered as a 404 problem detail when there is no
   *     organisation by that identity — the same answer a tenant naming another one gets
   */
  @GetMapping("/{id}")
  public OrganisationResponse read(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    OrganisationId organisation = new OrganisationId(id);
    return manageOrganisations
        .findOrganisation(actor, organisation)
        .map(OrganisationController::respond)
        .orElseThrow(() -> new UnknownOrganisationException(organisation));
  }

  private static OrganisationResponse respond(PublishedOrganisation organisation) {
    return new OrganisationResponse(organisation.id().value(), organisation.name());
  }
}
