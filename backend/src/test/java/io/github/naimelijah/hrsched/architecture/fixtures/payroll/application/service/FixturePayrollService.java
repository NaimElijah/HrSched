package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service;

import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.in.FixturePayUseCase;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.FixtureKernelValue;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the application and I/O rules: a use case over the JDK's collections, its own port, its own
 * domain and the kernel.
 */
public final class FixturePayrollService implements FixturePayUseCase {

  private final List<FixturePayslip> paid = new ArrayList<>();

  @Override
  public void paySomebody() {
    paid.add(new FixturePayslip(new FixtureKernelValue("fixture").text()));
  }
}
