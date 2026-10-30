package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.out;

import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;
import java.util.Optional;

/**
 * Breaks the tenancy rule: a lookup by reference alone, which reads any organisation's payslip
 * whose reference is known (ADR-009).
 */
public interface UnscopedPayslipRepository {

  /** The lookup the rule must reject. */
  Optional<FixturePayslip> findByReference(String reference);
}
