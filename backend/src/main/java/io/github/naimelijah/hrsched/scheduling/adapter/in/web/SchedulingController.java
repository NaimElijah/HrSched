package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.application.port.in.ConfigureConstraintsUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.DefineShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerateRosterCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ManageShiftsUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PlanRostersUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ReviseConstraintsCommand;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Driving adapter exposing the scheduling use cases over HTTP.
 *
 * <p>The organisation comes from the acting identity, never the request body.
 */
@RestController
@RequestMapping("/api")
public class SchedulingController {

  private final ManageShiftsUseCase manageShifts;
  private final ConfigureConstraintsUseCase configureConstraints;
  private final PlanRostersUseCase planRosters;

  public SchedulingController(
      ManageShiftsUseCase manageShifts,
      ConfigureConstraintsUseCase configureConstraints,
      PlanRostersUseCase planRosters) {
    this.manageShifts = manageShifts;
    this.configureConstraints = configureConstraints;
    this.planRosters = planRosters;
  }

  @PostMapping("/shifts")
  public ResponseEntity<ShiftResponse> defineShift(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @RequestBody DefineShiftRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    ShiftId id =
        manageShifts.defineShift(
            actor,
            new DefineShiftCommand(
                ActorFromHeaders.organisationOf(organisation),
                request.toPeriod(),
                request.requiredHeadcount(),
                request.toRequiredQualifications()));
    return ResponseEntity.created(URI.create("/api/shifts/" + id.value()))
        .body(
            new ShiftResponse(
                id.value(), request.start(), request.end(), request.requiredHeadcount()));
  }

  @PutMapping("/constraints")
  public ConstraintSetResponse reviseConstraints(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @RequestBody ConstraintSetRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    ConstraintSet revised =
        configureConstraints.reviseConstraints(
            actor,
            new ReviseConstraintsCommand(
                ActorFromHeaders.organisationOf(organisation), request.toConstraints()));
    return ConstraintSetResponse.from(revised);
  }

  @GetMapping("/constraints")
  public ConstraintSetResponse currentConstraints(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return ConstraintSetResponse.from(
        configureConstraints.currentConstraints(
            actor, ActorFromHeaders.organisationOf(organisation)));
  }

  @PostMapping("/rosters")
  public ResponseEntity<RosterResponse> generate(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @RequestBody PeriodRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    Roster draft =
        planRosters.generateDraft(
            actor,
            new GenerateRosterCommand(
                ActorFromHeaders.organisationOf(organisation), request.toPeriod()));
    return ResponseEntity.created(URI.create("/api/rosters/" + draft.id().value()))
        .body(RosterResponse.from(draft));
  }

  @PostMapping("/rosters/{id}/publication")
  public RosterResponse publish(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return RosterResponse.from(
        planRosters.publish(
            actor, ActorFromHeaders.organisationOf(organisation), new RosterId(id)));
  }

  @GetMapping("/rosters/{id}")
  public ResponseEntity<RosterResponse> find(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return planRosters
        .findRoster(actor, ActorFromHeaders.organisationOf(organisation), new RosterId(id))
        .map(RosterResponse::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
