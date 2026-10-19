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
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownConstraintVersionException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownShiftException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
      @AuthenticationPrincipal Actor actor, @RequestBody DefineShiftRequest request) {
    Set<QualificationId> required = request.toRequiredQualifications();
    ZoneId zone = request.toZone();
    ShiftId id =
        manageShifts.defineShift(
            actor,
            new DefineShiftCommand(
                actingIn(actor), request.toPeriod(), zone, request.requiredHeadcount(), required));
    return ResponseEntity.created(URI.create("/api/shifts/" + id.value()))
        .body(
            new ShiftResponse(
                id.value(),
                request.start(),
                request.end(),
                zone.getId(),
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
      @AuthenticationPrincipal Actor actor, @RequestParam Instant from, @RequestParam Instant to) {
    return manageShifts.findShifts(actor, actingIn(actor), new TimeRange(from, to)).stream()
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
  public ShiftResponse findShift(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    ShiftId shift = new ShiftId(id);
    return manageShifts
        .findShift(actor, actingIn(actor), shift)
        .map(ShiftResponse::from)
        .orElseThrow(() -> new UnknownShiftException(shift));
  }

  @PutMapping("/constraints")
  public ConstraintSetResponse reviseConstraints(
      @AuthenticationPrincipal Actor actor, @RequestBody ConstraintSetRequest request) {
    ConstraintSet revised =
        configureConstraints.reviseConstraints(
            actor, new ReviseConstraintsCommand(actingIn(actor), request.toConstraints()));
    return ConstraintSetResponse.from(revised);
  }

  @GetMapping("/constraints")
  public ConstraintSetResponse currentConstraints(@AuthenticationPrincipal Actor actor) {
    return ConstraintSetResponse.from(
        configureConstraints.currentConstraints(actor, actingIn(actor)));
  }

  /**
   * Reads back one version of the rules: what a roster published under it was judged against,
   * however they have changed since (Q9). Any member may, as for the current rules (F21).
   *
   * @param version which revision, as a roster names it
   * @return that version's rules, with their thresholds
   * @throws UnknownConstraintVersionException answered as a 404 problem detail when this
   *     organisation has no version by that number
   */
  @GetMapping("/constraints/{version}")
  public ConstraintSetResponse findConstraints(
      @AuthenticationPrincipal Actor actor, @PathVariable int version) {
    return configureConstraints
        .findConstraints(actor, actingIn(actor), version)
        .map(ConstraintSetResponse::from)
        .orElseThrow(() -> new UnknownConstraintVersionException(version));
  }

  @PostMapping("/rosters")
  public ResponseEntity<RosterResponse> generate(
      @AuthenticationPrincipal Actor actor, @RequestBody PeriodRequest request) {
    Roster draft =
        planRosters.generateDraft(
            actor, new GenerateRosterCommand(actingIn(actor), request.toPeriod()));
    return ResponseEntity.created(URI.create("/api/rosters/" + draft.id().value()))
        .body(RosterResponse.from(draft));
  }

  @GetMapping("/rosters")
  public List<RosterResponse> findRosters(@AuthenticationPrincipal Actor actor) {
    return planRosters.findRosters(actor, actingIn(actor)).stream()
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
      @AuthenticationPrincipal Actor actor, @RequestParam Instant from, @RequestParam Instant to) {
    return viewOwnSchedule.findOwnSchedule(actor, actingIn(actor), new TimeRange(from, to)).stream()
        .map(RosteredShiftResponse::from)
        .toList();
  }

  @PostMapping("/rosters/{id}/assignments")
  public RosterResponse assign(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @RequestBody AssignmentRequest request) {
    return RosterResponse.from(
        planRosters.assign(
            actor,
            new AssignToShiftCommand(
                actingIn(actor),
                new RosterId(id),
                request.requiredShift(),
                request.requiredEmployee())));
  }

  @DeleteMapping("/rosters/{id}/assignments/{shift}/{employee}")
  public RosterResponse unassign(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @PathVariable UUID shift,
      @PathVariable UUID employee) {
    return RosterResponse.from(
        planRosters.unassign(
            actor,
            new RemoveAssignmentCommand(
                actingIn(actor), new RosterId(id), new ShiftId(shift), new EmployeeId(employee))));
  }

  @PostMapping("/rosters/{id}/publication")
  public RosterResponse publish(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return RosterResponse.from(planRosters.publish(actor, actingIn(actor), new RosterId(id)));
  }

  /**
   * Reads one roster.
   *
   * @param id the roster to read
   * @return it
   * @throws UnknownRosterException answered as a 404 problem detail when this organisation has no
   *     roster by that identity
   */
  @GetMapping("/rosters/{id}")
  public RosterResponse find(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    RosterId roster = new RosterId(id);
    return planRosters
        .findRoster(actor, actingIn(actor), roster)
        .map(RosterResponse::from)
        .orElseThrow(() -> new UnknownRosterException(roster));
  }

  /**
   * What publishing a draft would say now, without publishing it (#205): every rule it breaks, and
   * every shift short of people. Advisory; publication decides.
   */
  @GetMapping("/rosters/{id}/assessment")
  public AssessmentResponse assess(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return AssessmentResponse.from(
        planRosters.findAssessment(actor, actingIn(actor), new RosterId(id)));
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
