package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.out;

import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;
import java.util.Optional;

/**
 * Breaks the tenancy rule: a lookup by reference alone, which reads any organisation's payslip
 * whose reference is known, and a question by reference alone, which tells whether one exists
 * anywhere (ADR-009).
 */
public interface UnscopedPayslipRepository {

  /** The lookup the rule must reject. */
  Optional<FixturePayslip> findByReference(String reference);

  /**
   * An overload of the lookup above, which the rule must reject even where an allow-list names that
   * lookup's own signature.
   */
  Optional<FixturePayslip> findByReference(String reference, int issue);

  /** A method named like no lookup, which the rule must reject as well. */
  boolean existsByReference(String reference);
}
