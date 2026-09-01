package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.Set;

/**
 * Raised when someone is assigned to a shift demanding qualifications they do not hold.
 *
 * <p>Rule R4. Distinct from an unknown employee because it is a rule being broken rather than a
 * record being missing, and it earns a 422 rather than a 404: the caller named real things and
 * asked for something the domain forbids.
 *
 * <p>Names which qualifications are missing, so the answer is actionable without a second request.
 * Identifiers only - a qualification's name belongs to the caller's own reading of it.
 */
public class AssigneeNotQualifiedException extends RuntimeException {

  public AssigneeNotQualifiedException(
      EmployeeId employee, ShiftId shift, Set<QualificationId> missing) {
    super(
        "Employee %s cannot cover shift %s: missing qualifications %s"
            .formatted(
                employee.value(),
                shift.value(),
                missing.stream().map(QualificationId::value).toList()));
  }
}
