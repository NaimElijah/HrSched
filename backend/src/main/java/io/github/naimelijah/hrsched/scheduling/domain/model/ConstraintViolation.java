package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A rule that a proposed roster would break, and for whom.
 *
 * <p>A working-time rule names itself. The rules that hold whatever the constraint set says - one
 * place at a time (R3, R35), the qualifications a shift requires (R4), declared unavailability
 * (R18), and belonging to the organisation (R5) - are named by the constants here, and built by the
 * factories beside them, so a refusal lists every kind of breach in one shape (ADR-042).
 *
 * <p>A detail names things by identifier only. It travels to whoever is refused, and the times
 * somebody declared they cannot work say something about their health or household, so an
 * unavailability breach names the shift and not the span.
 *
 * @param employee whose schedule breaks the rule
 * @param constraint which rule
 * @param detail what specifically is wrong, for a human reading the rejection
 */
public record ConstraintViolation(EmployeeId employee, String constraint, String detail) {

  /** One person on two pieces of work at once (R3), on this roster or beside another (R35). */
  public static final String OVERLAPPING_WORK = "overlapping work";

  /** Somebody on a shift without every qualification it requires (R4). */
  public static final String MISSING_QUALIFICATIONS = "required qualifications";

  /** Somebody on a shift running through time they declared they cannot work (R18). */
  public static final String DECLARED_UNAVAILABLE = "declared unavailability";

  /** Somebody who is not one of the organisation's rosterable people (R5). */
  public static final String NOT_OF_THE_ORGANISATION = "not of this organisation";

  public ConstraintViolation {
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(constraint, "constraint must not be null");
    Objects.requireNonNull(detail, "detail must not be null");
  }

  /**
   * @param employee who would be in two places at once
   * @param shift one of the two
   * @param overlapping the other, which starts no earlier
   * @param heldOn the published roster already carrying {@code overlapping}, when it is not this
   *     one - named, so the Scheduler can find the clash outside the draft in front of them
   * @return the breach of R3, or of R35 when {@code heldOn} is present
   */
  public static ConstraintViolation overlappingWork(
      EmployeeId employee, ShiftId shift, ShiftId overlapping, Optional<RosterId> heldOn) {
    return new ConstraintViolation(
        employee,
        OVERLAPPING_WORK,
        heldOn
            .map(
                roster ->
                    "shift %s overlaps shift %s, which they already work on published roster %s"
                        .formatted(shift.value(), overlapping.value(), roster.value()))
            .orElseGet(
                () -> "shifts %s and %s overlap".formatted(shift.value(), overlapping.value())));
  }

  /**
   * @param employee who is on the shift
   * @param shift the shift requiring what they lack
   * @param missing the qualifications they lack, never empty
   * @return the breach of R4
   */
  public static ConstraintViolation missingQualifications(
      EmployeeId employee, ShiftId shift, Set<QualificationId> missing) {
    if (missing.isEmpty()) {
      throw new IllegalArgumentException("A missing qualification has to name one");
    }
    return new ConstraintViolation(
        employee,
        MISSING_QUALIFICATIONS,
        "shift %s requires qualifications they do not hold: %s"
            .formatted(
                shift.value(),
                missing.stream()
                    .map(qualification -> qualification.value().toString())
                    .sorted()
                    .collect(Collectors.joining(", "))));
  }

  /**
   * @param employee who declared they cannot work then
   * @param shift the shift running through that time
   * @return the breach of R18, naming the shift and never the span they declared
   */
  public static ConstraintViolation declaredUnavailable(EmployeeId employee, ShiftId shift) {
    return new ConstraintViolation(
        employee,
        DECLARED_UNAVAILABLE,
        "shift %s runs through time they declared they cannot work".formatted(shift.value()));
  }

  /**
   * @param employee somebody the organisation does not roster
   * @param shift the shift they are on
   * @return the breach of R5
   */
  public static ConstraintViolation notOfTheOrganisation(EmployeeId employee, ShiftId shift) {
    return new ConstraintViolation(
        employee,
        NOT_OF_THE_ORGANISATION,
        "shift %s carries somebody who is not one of this organisation's rosterable people"
            .formatted(shift.value()));
  }
}
