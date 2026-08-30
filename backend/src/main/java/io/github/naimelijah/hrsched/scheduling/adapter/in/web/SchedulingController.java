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
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
                id.value(),
                request.start(),
                request.end(),
                request.requiredHeadcount(),
                List.copyOf(request.toRequiredQualifications()).stream()
                    .map(QualificationId::value)
                    .toList()));
  }

  /**
   * @param from start of the span to look inside, an ISO-8601 instant
   * @param to end of that span
   * @return every shift overlapping it
   */
  @GetMapping("/shifts")
  public List<ShiftResponse> findShifts(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @RequestParam Instant from,
      @RequestParam Instant to) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return manageShifts
        .findShifts(actor, ActorFromHeaders.organisationOf(organisation), new TimeRange(from, to))
        .stream()
        .map(ShiftResponse::from)
        .toList();
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

  @GetMapping("/rosters")
  public List<RosterResponse> findRosters(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return planRosters.findRosters(actor, ActorFromHeaders.organisationOf(organisation)).stream()
        .map(RosterResponse::from)
        .toList();
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
