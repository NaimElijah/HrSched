package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.application.port.in.DefineQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageQualificationsUseCase;
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
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @RequestBody DefineQualificationRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    QualificationId id =
        manageQualifications.defineQualification(
            actor,
            new DefineQualificationCommand(
                ActorFromHeaders.organisationOf(organisation), request.requiredName()));
    return ResponseEntity.created(URI.create("/api/qualifications/" + id.value()))
        .body(new QualificationResponse(id.value(), request.requiredName()));
  }

  @GetMapping
  public List<QualificationResponse> list(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return manageQualifications
        .findQualifications(actor, ActorFromHeaders.organisationOf(organisation))
        .stream()
        .map(
            qualification ->
                new QualificationResponse(qualification.id().value(), qualification.name()))
        .toList();
  }

  @GetMapping("/{id}")
  public ResponseEntity<QualificationResponse> find(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return manageQualifications
        .findQualification(
            actor, ActorFromHeaders.organisationOf(organisation), new QualificationId(id))
        .map(
            qualification ->
                new QualificationResponse(qualification.id().value(), qualification.name()))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
