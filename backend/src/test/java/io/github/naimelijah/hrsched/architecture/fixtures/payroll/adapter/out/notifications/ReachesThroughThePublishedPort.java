package io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.out.notifications;

import io.github.naimelijah.hrsched.architecture.fixtures.notifications.application.port.in.FixtureNotifyUseCase;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.FixtureKernelValue;

/**
 * Keeps the cross-context rule: payroll reaches notifications only through its driving port, and
 * otherwise touches only its own domain and the kernel.
 */
public final class ReachesThroughThePublishedPort {

  /** References the rule must permit. */
  public FixturePayslip notifyThrough(FixtureNotifyUseCase useCase, FixtureKernelValue value) {
    useCase.notifySomebody();
    return new FixturePayslip(value.text());
  }
}
