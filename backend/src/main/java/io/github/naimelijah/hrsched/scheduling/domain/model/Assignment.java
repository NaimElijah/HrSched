package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import java.util.Objects;

/**
 * One person covering one shift.
 *
 * <p>References the employee by identity only. Scheduling never holds a workforce entity, which is
 * both a boundary the architecture rules enforce and the reason a roster stays readable without
 * loading the whole workforce.
 *
 * @param shift which shift is covered
 * @param employee who covers it
 */
public record Assignment(ShiftId shift, EmployeeId employee) {

  public Assignment {
    Objects.requireNonNull(shift, "shift must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
  }
}
