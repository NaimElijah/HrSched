package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.application.port.in.AddEmployeeCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.DeclareUnavailabilityCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.FindRosterableEmployeesUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.GrantQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageEmployeesUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.SetContractTermsCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.StatePreferenceCommand;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownEmployeeException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

  public EmployeeController(
      ManageEmployeesUseCase manageEmployees, FindRosterableEmployeesUseCase findEmployees) {
    this.manageEmployees = manageEmployees;
    this.findEmployees = findEmployees;
  }

  @PostMapping
  public ResponseEntity<EmployeeResponse> add(
      @AuthenticationPrincipal Actor actor, @RequestBody AddEmployeeRequest request) {
    OrganisationId org = actingIn(actor);
    EmployeeId id =
        manageEmployees.addEmployee(actor, new AddEmployeeCommand(org, request.requiredName()));
    return ResponseEntity.created(URI.create("/api/employees/" + id.value()))
        .body(EmployeeResponse.justAdded(id.value(), request.requiredName()));
  }

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

  @PutMapping("/{id}/contract")
  public ResponseEntity<Void> setContract(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @RequestBody ContractRequest request) {
    manageEmployees.setContractTerms(
        actor,
        new SetContractTermsCommand(
            actingIn(actor),
            new EmployeeId(id),
            Duration.ofMinutes(request.weeklyContractedMinutes())));
    return ResponseEntity.noContent().build();
  }

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
