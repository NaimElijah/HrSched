package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;

/**
 * Raised when somebody asks to hand a shift to themself.
 *
 * <p>An invariant rather than a validation, because a swap that changes nothing would still travel
 * the whole approval path and publish a replacement roster identical to the one it superseded — so
 * the harm is not the nonsense request, it is the real roster churn behind it.
 */
public class SwapWithThemselfException extends RuntimeException {

  public SwapWithThemselfException(EmployeeId employee) {
    super("Employee %s cannot swap a shift with themself".formatted(employee.value()));
  }
}
