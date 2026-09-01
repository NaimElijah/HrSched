package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.application.port.in.RosterableEmployee;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * What a caller is told about somebody who can be rostered.
 *
 * <p>Carries unavailability and contracted hours because requirement F4 asks a Scheduler to see the
 * availability of every employee, and until this did, only the use case satisfied that row while
 * the HTTP listing quietly did not.
 *
 * <p>This is personal data - when somebody cannot work says things about their health and their
 * household - which is why the listing behind it is restricted to Admins and Schedulers. It is the
 * one response in this API where that restriction is the only thing standing between a colleague
 * and somebody's medical appointments.
 *
 * @param id the employee identity
 * @param name their name
 * @param qualifications the qualifications they hold
 * @param unavailability the spans they have declared they cannot work
 * @param weeklyContractedHours their contracted hours as an ISO-8601 duration, absent if none are
 *     set
 */
public record EmployeeResponse(
    UUID id,
    String name,
    List<UUID> qualifications,
    List<PeriodResponse> unavailability,
    Long weeklyContractedMinutes) {

  static EmployeeResponse from(RosterableEmployee employee) {
    return new EmployeeResponse(
        employee.id().value(),
        employee.name(),
        employee.qualifications().stream().map(QualificationId::value).toList(),
        employee.unavailability().stream().map(PeriodResponse::from).toList(),
        employee.weeklyContractedHours().map(Duration::toMinutes).orElse(null));
  }

  /**
   * Somebody who has just been added: no qualifications, no declared absence and no contract yet.
   *
   * @param id their new identity
   * @param name what they are called
   * @return the response for a freshly added employee
   */
  static EmployeeResponse justAdded(UUID id, String name) {
    return new EmployeeResponse(id, name, List.of(), List.of(), null);
  }
}
