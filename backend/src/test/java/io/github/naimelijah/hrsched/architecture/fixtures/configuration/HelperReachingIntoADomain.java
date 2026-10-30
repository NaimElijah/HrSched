package io.github.naimelijah.hrsched.architecture.fixtures.configuration;

import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;

/**
 * Breaks the composition-root rule: an ordinary class that only lives beside the wiring, reaching
 * into payroll's domain as if it were the wiring.
 */
public final class HelperReachingIntoADomain {

  /** The reference the rule must reject. */
  public FixturePayslip payslip() {
    return new FixturePayslip("fixture");
  }
}
