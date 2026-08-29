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
      @RequestBody AddEmployeeRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role);
    OrganisationId org = new OrganisationId(UUID.fromString(organisation));
    EmployeeId id = manageEmployees.addEmployee(actor, new AddEmployeeCommand(org, request.name()));
    return ResponseEntity.created(URI.create("/api/employees/" + id.value()))
        .body(new EmployeeResponse(id.value(), request.name(), List.of()));
  }

  @GetMapping
  public List<EmployeeResponse> list(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role);
    return findEmployees
        .findRosterable(actor, new OrganisationId(UUID.fromString(organisation)))
        .stream()
        .map(
            employee ->
                new EmployeeResponse(
                    employee.id().value(),
                    employee.name(),
                    employee.qualifications().stream().map(QualificationId::value).toList()))
        .toList();
  }

  @PutMapping("/{id}/qualifications/{qualification}")
  public ResponseEntity<Void> grantQualification(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @PathVariable UUID id,
      @PathVariable UUID qualification) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role);
    manageEmployees.grantQualification(
        actor,
        new GrantQualificationCommand(
            new OrganisationId(UUID.fromString(organisation)),
            new EmployeeId(id),
            new QualificationId(qualification)));
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}/contract")
  public ResponseEntity<Void> setContract(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @PathVariable UUID id,
      @RequestBody ContractRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role);
    manageEmployees.setContractTerms(
        actor,
        new SetContractTermsCommand(
            new OrganisationId(UUID.fromString(organisation)),
            new EmployeeId(id),
            Duration.ofMinutes(request.weeklyContractedMinutes())));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/unavailability")
  public ResponseEntity<Void> declareUnavailability(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @PathVariable UUID id,
      @RequestBody PeriodRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role);
    manageEmployees.declareUnavailability(
        actor,
        new DeclareUnavailabilityCommand(
            new OrganisationId(UUID.fromString(organisation)),
            new EmployeeId(id),
            new TimeRange(request.start(), request.end())));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/preferences")
  public ResponseEntity<Void> statePreference(
      @RequestHeader("X-Organisation") String organisation,
      @RequestHeader("X-User") String user,
      @RequestHeader("X-Role") String role,
      @PathVariable UUID id,
      @RequestBody PreferenceRequest request) {
    Actor actor = ActorFromHeaders.resolve(organisation, user, role);
    manageEmployees.statePreference(
        actor,
        new StatePreferenceCommand(
            new OrganisationId(UUID.fromString(organisation)),
            new EmployeeId(id),
            new TimeRange(request.start(), request.end()),
            request.strength()));
    return ResponseEntity.noContent().build();
  }
}
