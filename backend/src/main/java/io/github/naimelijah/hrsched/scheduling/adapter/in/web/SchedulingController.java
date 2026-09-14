package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.application.port.in.AssignToShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ConfigureConstraintsUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.DefineShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerateRosterCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ManageShiftsUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PlanRostersUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RemoveAssignmentCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ReviseConstraintsCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ViewOwnScheduleUseCase;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownShiftException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
  private final ViewOwnScheduleUseCase viewOwnSchedule;

  public SchedulingController(
      ManageShiftsUseCase manageShifts,
      ConfigureConstraintsUseCase configureConstraints,
      PlanRostersUseCase planRosters,
      ViewOwnScheduleUseCase viewOwnSchedule) {
    this.manageShifts = manageShifts;
    this.configureConstraints = configureConstraints;
    this.planRosters = planRosters;
    this.viewOwnSchedule = viewOwnSchedule;
  }

  @PostMapping("/shifts")
  public ResponseEntity<ShiftResponse> defineShift(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @RequestBody DefineShiftRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    Set<QualificationId> required = request.toRequiredQualifications();
    ShiftId id =
        manageShifts.defineShift(
            actor,
            new DefineShiftCommand(
                ActorFromHeaders.organisationOf(organisation),
                request.toPeriod(),
                request.requiredHeadcount(),
                required));
    return ResponseEntity.created(URI.create("/api/shifts/" + id.value()))
        .body(
            new ShiftResponse(
                id.value(),
                request.start(),
                request.end(),
                request.requiredHeadcount(),
                required.stream().map(QualificationId::value).toList()));
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

  /**
   * Reads one shift. Any member of the organisation may, as for the listing: a shift is work that
   * needs covering, not a person.
   *
   * @param id the shift to read
   * @return it
   * @throws UnknownShiftException answered as a 404 problem detail when this organisation has no
   *     shift by that identity
   */
  @GetMapping("/shifts/{id}")
  public ShiftResponse findShift(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    ShiftId shift = new ShiftId(id);
    return manageShifts
        .findShift(actor, ActorFromHeaders.organisationOf(organisation), shift)
        .map(ShiftResponse::from)
        .orElseThrow(() -> new UnknownShiftException(shift));
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

  /**
   * The work the caller themself is rostered for (requirement F5).
   *
   * <p>Needs no role: it answers only for the personnel record the caller carries, so it discloses
   * nothing about a colleague. Reading a whole roster still needs Admin or Scheduler (R12).
   *
   * @param from start of the span to look inside, an ISO-8601 instant
   * @param to end of that span
   * @return their published work overlapping it, earliest first
   */
  @GetMapping("/me/schedule")
  public List<RosteredShiftResponse> findOwnSchedule(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @RequestParam Instant from,
      @RequestParam Instant to) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return viewOwnSchedule
        .findOwnSchedule(
            actor, ActorFromHeaders.organisationOf(organisation), new TimeRange(from, to))
        .stream()
        .map(RosteredShiftResponse::from)
        .toList();
  }

  @PostMapping("/rosters/{id}/assignments")
  public RosterResponse assign(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id,
      @RequestBody AssignmentRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return RosterResponse.from(
        planRosters.assign(
            actor,
            new AssignToShiftCommand(
                ActorFromHeaders.organisationOf(organisation),
                new RosterId(id),
                request.requiredShift(),
                request.requiredEmployee())));
  }

  @DeleteMapping("/rosters/{id}/assignments/{shift}/{employee}")
  public RosterResponse unassign(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id,
      @PathVariable UUID shift,
      @PathVariable UUID employee) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return RosterResponse.from(
        planRosters.unassign(
            actor,
            new RemoveAssignmentCommand(
                ActorFromHeaders.organisationOf(organisation),
                new RosterId(id),
                new ShiftId(shift),
                new EmployeeId(employee))));
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
