package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.workforce.application.port.in.RosterableEmployee;
import java.time.Duration;
import java.util.Comparator;
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
 * household - which is why the listing behind it is restricted to Admins and Schedulers. It is not
 * the only response carrying it: {@code GET /api/employees/{id}/unavailability} answers with the
 * same spans, to the person themself as well. For somebody else's, both are refused to anyone but
 * an Admin or a Scheduler, and that restriction is the only thing standing between a colleague and
 * somebody's medical appointments.
 *
 * @param id the employee identity
 * @param name their name
 * @param qualifications the qualifications they hold, ordered by identity as text: they are a set,
 *     with no order of their own, and a response that changed order between two identical reads
 *     would look like a change. Text rather than {@link UUID#compareTo}, which compares signed
 *     halves, so the order is the one PostgreSQL sorts a {@code uuid} in
 * @param unavailability the spans they have declared they cannot work
 * @param weeklyContractedMinutes their contracted minutes per week, absent if none are set. Minutes
 *     rather than a duration string, matching {@code ContractRequest} so the wire format carries no
 *     duration parsing in either direction
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
        employee.qualifications().stream()
            .map(QualificationId::value)
            .sorted(Comparator.comparing(UUID::toString))
            .toList(),
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
