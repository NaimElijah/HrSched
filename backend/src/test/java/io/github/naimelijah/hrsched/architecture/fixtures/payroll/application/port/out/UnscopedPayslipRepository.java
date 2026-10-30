package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.out;

import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;
import java.util.List;
import java.util.Optional;

/**
 * Breaks the tenancy rule: a lookup by reference alone, which reads any organisation's payslip
 * whose reference is known, a question by reference alone, which tells whether one exists anywhere,
 * a lock over every organisation's payroll, and a listing of every payslip there is (ADR-009).
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

  /** A lock taken across every organisation, which the rule must reject. */
  void lockPayroll();

  /**
   * A listing with the name and signature of one the build allows on another port, which the rule
   * must still reject here.
   */
  List<FixturePayslip> findAll();
}
