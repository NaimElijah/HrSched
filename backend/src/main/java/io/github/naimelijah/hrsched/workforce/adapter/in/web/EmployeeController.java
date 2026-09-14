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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @RequestBody AddEmployeeRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    OrganisationId org = ActorFromHeaders.organisationOf(organisation);
    EmployeeId id =
        manageEmployees.addEmployee(actor, new AddEmployeeCommand(org, request.requiredName()));
    return ResponseEntity.created(URI.create("/api/employees/" + id.value()))
        .body(EmployeeResponse.justAdded(id.value(), request.requiredName()));
  }

  @GetMapping
  public List<EmployeeResponse> list(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    return findEmployees
        .findRosterable(actor, ActorFromHeaders.organisationOf(organisation))
        .stream()
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
  public EmployeeResponse find(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    EmployeeId employee = new EmployeeId(id);
    return manageEmployees
        .findEmployee(actor, ActorFromHeaders.organisationOf(organisation), employee)
        .map(EmployeeResponse::from)
        .orElseThrow(() -> new UnknownEmployeeException(employee));
  }

  @PutMapping("/{id}/qualifications/{qualification}")
  public ResponseEntity<Void> grantQualification(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id,
      @PathVariable UUID qualification) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    manageEmployees.grantQualification(
        actor,
        new GrantQualificationCommand(
            ActorFromHeaders.organisationOf(organisation),
            new EmployeeId(id),
            new QualificationId(qualification)));
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}/contract")
  public ResponseEntity<Void> setContract(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id,
      @RequestBody ContractRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    manageEmployees.setContractTerms(
        actor,
        new SetContractTermsCommand(
            ActorFromHeaders.organisationOf(organisation),
            new EmployeeId(id),
            Duration.ofMinutes(request.weeklyContractedMinutes())));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/unavailability")
  public ResponseEntity<Void> declareUnavailability(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id,
      @RequestBody PeriodRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    manageEmployees.declareUnavailability(
        actor,
        new DeclareUnavailabilityCommand(
            ActorFromHeaders.organisationOf(organisation), new EmployeeId(id), request.toPeriod()));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/preferences")
  public ResponseEntity<Void> statePreference(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @RequestHeader(value = "X-Employee", required = false) String actingAs,
      @PathVariable UUID id,
      @RequestBody PreferenceRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role, actingAs);
    manageEmployees.statePreference(
        actor,
        new StatePreferenceCommand(
            ActorFromHeaders.organisationOf(organisation),
            new EmployeeId(id),
            request.toPeriod(),
            request.requiredStrength()));
    return ResponseEntity.noContent().build();
  }
}
