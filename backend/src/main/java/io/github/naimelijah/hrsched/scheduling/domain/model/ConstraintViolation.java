package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import java.util.Objects;

/**
 * A rule that a proposed roster would break, and for whom.
 *
 * @param employee whose schedule breaks the rule
 * @param constraint which rule
 * @param detail what specifically is wrong, for a human reading the rejection
 */
public record ConstraintViolation(EmployeeId employee, String constraint, String detail) {

  public ConstraintViolation {
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(constraint, "constraint must not be null");
    Objects.requireNonNull(detail, "detail must not be null");
  }
}
