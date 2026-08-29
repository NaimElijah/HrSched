package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.application.port.in.DefineQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageQualificationsUseCase;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestBody DefineQualificationRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role);
    QualificationId id =
        manageQualifications.defineQualification(
            actor,
            new DefineQualificationCommand(
                new OrganisationId(UUID.fromString(organisation)), request.name()));
    return ResponseEntity.created(URI.create("/api/qualifications/" + id.value()))
        .body(new QualificationResponse(id.value(), request.name()));
  }

  @GetMapping
  public List<QualificationResponse> list(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role);
    return manageQualifications
        .findQualifications(actor, new OrganisationId(UUID.fromString(organisation)))
        .stream()
        .map(
            qualification ->
                new QualificationResponse(qualification.id().value(), qualification.name()))
        .toList();
  }
}
