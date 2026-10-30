package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.in;

/** A driving port of the fixture {@code payroll} context: the part it publishes. */
public interface FixturePayUseCase {

  /** Pays somebody. */
  void paySomebody();
}
