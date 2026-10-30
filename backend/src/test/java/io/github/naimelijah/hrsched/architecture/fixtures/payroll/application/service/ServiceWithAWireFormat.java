package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service;

import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;
import tools.jackson.databind.ObjectMapper;

/** Breaks the application rule with a library no deny-list named: a use case writing JSON. */
public final class ServiceWithAWireFormat {

  private final ObjectMapper json = new ObjectMapper();

  /** The use the rule must reject. */
  public String describe(FixturePayslip payslip) {
    return json.writeValueAsString(payslip);
  }
}
