package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.application.port.in.DefineQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageQualificationsUseCase;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownQualificationException;
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

/** Driving adapter exposing qualification management over HTTP. */
@RestController
@RequestMapping("/api/qualifications")
public class QualificationController {

  private final ManageQualificationsUseCase manageQualifications;

  public QualificationController(ManageQualificationsUseCase manageQualifications) {
    this.manageQualifications = manageQualifications;
  }

  @PostMapping
  public ResponseEntity<QualificationResponse> define(
      @AuthenticationPrincipal Actor actor, @RequestBody DefineQualificationRequest request) {
    OrganisationId organisation = actingIn(actor);
    String name = request.requiredName();
    QualificationId id =
        manageQualifications.defineQualification(
            actor, new DefineQualificationCommand(organisation, name));
    return ResponseEntity.created(URI.create("/api/qualifications/" + id.value()))
        .body(new QualificationResponse(id.value(), name));
  }

  @GetMapping
  public List<QualificationResponse> list(@AuthenticationPrincipal Actor actor) {
    return manageQualifications.findQualifications(actor, actingIn(actor)).stream()
        .map(QualificationResponse::from)
        .toList();
  }

  /**
   * Reads one qualification. Any member of the organisation may, as for the listing.
   *
   * @param id the qualification to read
   * @return it
   * @throws UnknownQualificationException answered as a 404 problem detail when this organisation
   *     defines none by that identity
   */
  @GetMapping("/{id}")
  public QualificationResponse find(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    QualificationId qualification = new QualificationId(id);
    return manageQualifications
        .findQualification(actor, actingIn(actor), qualification)
        .map(QualificationResponse::from)
        .orElseThrow(() -> new UnknownQualificationException(qualification));
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
