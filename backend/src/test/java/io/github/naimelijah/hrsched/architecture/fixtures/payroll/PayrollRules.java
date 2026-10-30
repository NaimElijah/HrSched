package io.github.naimelijah.hrsched.architecture.fixtures.payroll;

/**
 * Breaks the layer rule: a class directly in payroll's root package, in none of its layers, where
 * neither the domain's rules nor the application's would see it.
 */
public final class PayrollRules {

  private PayrollRules() {}
}
