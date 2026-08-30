package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import java.util.UUID;

/**
 * One broken rule, in the three parts a caller needs to act on it.
 *
 * <p>Deliberately structured rather than a formatted sentence. A caller showing this to a scheduler
 * has to resolve the employee to a name and group by person, and doing that from prose means
 * parsing a string this service happens to format a particular way.
 *
 * @param employee whose schedule breaks the rule
 * @param constraint which rule, by its own name
 * @param detail what specifically is wrong
 */
public record ViolationResponse(UUID employee, String constraint, String detail) {

  static ViolationResponse from(ConstraintViolation violation) {
    return new ViolationResponse(
        violation.employee().value(), violation.constraint(), violation.detail());
  }
}
