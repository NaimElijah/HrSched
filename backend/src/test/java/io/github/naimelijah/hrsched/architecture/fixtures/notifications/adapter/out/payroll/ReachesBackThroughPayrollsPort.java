package io.github.naimelijah.hrsched.architecture.fixtures.notifications.adapter.out.payroll;

import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.in.FixturePayUseCase;

/**
 * Closes a cycle between two contexts out of references each legal alone: notifications reaches
 * payroll's published port from an adapter, as payroll reaches notifications' in {@code
 * ReachesThroughThePublishedPort}.
 */
public final class ReachesBackThroughPayrollsPort {

  /** The reference that, beside payroll's, the context cycle rule must reject. */
  public void payThrough(FixturePayUseCase useCase) {
    useCase.paySomebody();
  }
}
