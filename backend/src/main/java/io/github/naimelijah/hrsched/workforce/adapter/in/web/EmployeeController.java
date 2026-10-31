package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.workforce.application.port.in.AddEmployeeCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.DeclareUnavailabilityCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.FindRosterableEmployeesUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.GrantQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageEmployeesUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.SetContractTermsCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.StatePreferenceCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.WithdrawPreferenceCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.WithdrawUnavailabilityCommand;
import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownDeclarationException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownEmployeeException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
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
 * Driving adapter exposing the workforce use cases over HTTP.
 *
 * <p>The organisation is taken from the acting identity, never from the request body. Letting a
 * caller name the organisation they are writing to would reduce the tenant check to a formality.
 */
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

  private final ManageEmployeesUseCase manageEmployees;
  private final FindRosterableEmployeesUseCase findEmployees;

  /**
   * @param manageEmployees the use case behind every write and every read of one person
   * @param findEmployees the use case behind the listing of everybody the organisation could roster
   */
  public EmployeeController(
      ManageEmployeesUseCase manageEmployees, FindRosterableEmployeesUseCase findEmployees) {
    this.manageEmployees = manageEmployees;
    this.findEmployees = findEmployees;
  }

  /**
   * Adds an employee to the acting organisation.
   *
   * @param request the new employee's name
   * @return 201 with the employee and its location. A missing, blank or unstorable name is 400, and
   *     an actor who is not an Admin is 403
   */
  @PostMapping
  public ResponseEntity<EmployeeResponse> add(
      @AuthenticationPrincipal Actor actor, @RequestBody AddEmployeeRequest request) {
    OrganisationId org = actingIn(actor);
    String name = request.requiredName();
    EmployeeId id = manageEmployees.addEmployee(actor, new AddEmployeeCommand(org, name));
    return ResponseEntity.created(URI.create("/api/employees/" + id.value()))
        .body(EmployeeResponse.justAdded(id.value(), name));
  }

  /**
   * Lists everybody the acting organisation could roster, absences and contracted hours included.
   *
   * @return every employee, ordered by name. An actor who is neither an Admin nor a Scheduler is
   *     answered 403, as the absences are personal data
   */
  @GetMapping
  public List<EmployeeResponse> list(@AuthenticationPrincipal Actor actor) {
    return findEmployees.findRosterable(actor, actingIn(actor)).stream()
        .map(EmployeeResponse::from)
        .toList();
  }

  /**
   * Reads one person, authorised as the listing is.
   *
   * @param id the employee to read
   * @return them, carrying the same absences and contract the listing does
   * @throws UnknownEmployeeException answered as a 404 problem detail when this organisation holds
   *     nobody by that identity; a role that may not list people is answered 403 before that
   */
  @GetMapping("/{id}")
  public EmployeeResponse find(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    EmployeeId employee = new EmployeeId(id);
    return manageEmployees
        .findEmployee(actor, actingIn(actor), employee)
        .map(EmployeeResponse::from)
        .orElseThrow(() -> new UnknownEmployeeException(employee));
  }

  /**
   * Records that an employee holds a qualification, which a repeat of the same request leaves as it
   * was.
   *
   * @param id the employee
   * @param qualification the qualification, one this organisation defines
   * @return 204. An actor who is not an Admin is answered 403, and an employee or qualification
   *     this organisation does not hold, 404
   */
  @PutMapping("/{id}/qualifications/{qualification}")
  public ResponseEntity<Void> grantQualification(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @PathVariable UUID qualification) {
    manageEmployees.grantQualification(
        actor,
        new GrantQualificationCommand(
            actingIn(actor), new EmployeeId(id), new QualificationId(qualification)));
    return ResponseEntity.noContent().build();
  }

  /**
   * Sets what an employee is contracted to work, replacing any terms they had.
   *
   * @param id the employee
   * @param request the contracted minutes per week
   * @return 204. Minutes that are not positive, or beyond what a duration holds, are answered 400;
   *     an actor who is not an Admin, 403; an employee this organisation does not hold, 404
   */
  @PutMapping("/{id}/contract")
  public ResponseEntity<Void> setContract(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @RequestBody ContractRequest request) {
    manageEmployees.setContractTerms(
        actor,
        new SetContractTermsCommand(
            actingIn(actor), new EmployeeId(id), request.weeklyContractedHours()));
    return ResponseEntity.noContent().build();
  }

  /**
   * Declares a span an employee cannot work, by the employee themselves or an Admin or Scheduler on
   * their behalf (F6).
   *
   * @param id who is unavailable
   * @param request the span
   * @return 204. A span missing a start or an end, ending at or before its start, or beyond what
   *     the store holds is answered 400; one overlapping a span already declared, 409; somebody
   *     else without an Admin or Scheduler role, 403; an employee this organisation does not hold,
   *     404
   */
  @PostMapping("/{id}/unavailability")
  public ResponseEntity<Void> declareUnavailability(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @RequestBody PeriodRequest request) {
    manageEmployees.declareUnavailability(
        actor,
        new DeclareUnavailabilityCommand(actingIn(actor), new EmployeeId(id), request.toPeriod()));
    return ResponseEntity.noContent().build();
  }

  /**
   * Reads back the spans somebody declared they cannot work (F6), authorised as declaring is.
   *
   * @param id whose declarations to read
   * @return their declared unavailability
   * @throws UnknownEmployeeException answered as a 404 problem detail when this organisation holds
   *     nobody by that identity; somebody else without an Admin or Scheduler role is answered 403
   *     before that
   */
  @GetMapping("/{id}/unavailability")
  public List<PeriodResponse> findUnavailability(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return manageEmployees.findUnavailability(actor, actingIn(actor), new EmployeeId(id)).stream()
        .map(PeriodResponse::from)
        .toList();
  }

  /**
   * Withdraws a span somebody declared they cannot work (F26), authorised as declaring is.
   *
   * <p>The span is named in the query, exactly as it was declared, because a declaration has no
   * identifier of its own and a DELETE carries no body (ADR-040). A span that does not end after it
   * starts is answered 400.
   *
   * @param id who declared it
   * @param start when the declared span begins, an ISO-8601 instant
   * @param end when it ends
   * @return 204 once it is withdrawn
   * @throws UnknownDeclarationException answered as a 404 problem detail when they declared no span
   *     exactly like it
   */
  @DeleteMapping("/{id}/unavailability")
  public ResponseEntity<Void> withdrawUnavailability(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @RequestParam Instant start,
      @RequestParam Instant end) {
    manageEmployees.withdrawUnavailability(
        actor,
        new WithdrawUnavailabilityCommand(
            actingIn(actor), new EmployeeId(id), new TimeRange(start, end)));
    return ResponseEntity.noContent().build();
  }

  /**
   * Reads back the soft preferences somebody stated (F6), authorised as stating one is.
   *
   * @param id whose preferences to read
   * @return their stated preferences
   * @throws UnknownEmployeeException answered as a 404 problem detail when this organisation holds
   *     nobody by that identity; somebody else without an Admin or Scheduler role is answered 403
   *     before that
   */
  @GetMapping("/{id}/preferences")
  public List<PreferenceResponse> findPreferences(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return manageEmployees.findPreferences(actor, actingIn(actor), new EmployeeId(id)).stream()
        .map(PreferenceResponse::from)
        .toList();
  }

  /**
   * States a soft preference about when an employee would rather work, by the employee themselves
   * or an Admin or Scheduler on their behalf (F6).
   *
   * @param id whose preference it is
   * @param request the span and how they feel about it
   * @return 204. A missing strength, start or end, an invalid span, or one beyond what the store
   *     holds is answered 400; somebody else without an Admin or Scheduler role, 403; an employee
   *     this organisation does not hold, 404
   */
  @PostMapping("/{id}/preferences")
  public ResponseEntity<Void> statePreference(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @RequestBody PreferenceRequest request) {
    manageEmployees.statePreference(
        actor,
        new StatePreferenceCommand(
            actingIn(actor), new EmployeeId(id), request.toPeriod(), request.requiredStrength()));
    return ResponseEntity.noContent().build();
  }

  /**
   * Withdraws a soft preference somebody stated (F26), named in the query by its span and strength
   * (ADR-040). Every identical statement goes with it. A span that does not end after it starts is
   * answered 400.
   *
   * @param id who stated it
   * @param start when the stated span begins, an ISO-8601 instant
   * @param end when it ends
   * @param strength how they said they felt about it
   * @return 204 once it is withdrawn
   * @throws UnknownDeclarationException answered as a 404 problem detail when they stated no
   *     preference exactly like it
   */
  @DeleteMapping("/{id}/preferences")
  public ResponseEntity<Void> withdrawPreference(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @RequestParam Instant start,
      @RequestParam Instant end,
      @RequestParam PreferenceStrength strength) {
    manageEmployees.withdrawPreference(
        actor,
        new WithdrawPreferenceCommand(
            actingIn(actor), new EmployeeId(id), new TimeRange(start, end), strength));
    return ResponseEntity.noContent().build();
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
