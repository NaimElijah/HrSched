package io.github.naimelijah.hrsched.workforce.application.port.in;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * What workforce's own screens are told about somebody: what any context may be told, plus their
 * contracted hours.
 *
 * <p>Workforce's own read type, not its published language. Contracted hours are personal data no
 * other context needs, so this context's web adapter reads them from here, through {@link
 * FindEmployeeRecordsUseCase}, rather than from {@link RosterableEmployee}. Like everything in this
 * package it is reachable from another context; it is kept home by no other context's adapter
 * consuming the port that returns it.
 *
 * @param employee what another context would be told about them
 * @param weeklyContractedHours their contracted hours, absent for casual staff
 */
public record EmployeeRecord(
    RosterableEmployee employee, Optional<Duration> weeklyContractedHours) {

  public EmployeeRecord {
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(weeklyContractedHours, "weeklyContractedHours must not be null");
  }
}
