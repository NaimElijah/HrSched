package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.out;

import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Optional;

/**
 * Keeps the tenancy rule: its lookup and its lock take the organisation, and a save, which is no
 * lookup, need not.
 */
public interface ScopedPayslipRepository {

  /** A write the rule does not ask about, as the aggregate carries its own organisation. */
  void save(FixturePayslip payslip);

  /** A lookup the rule must permit. */
  Optional<FixturePayslip> findByReference(OrganisationId organisation, String reference);

  /** A lock the rule must permit. */
  void lockPayrollOf(OrganisationId organisation);
}
