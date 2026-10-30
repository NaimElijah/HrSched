package io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.in.web;

import io.github.naimelijah.hrsched.architecture.fixtures.notifications.application.port.in.FixtureNotifyUseCase;

/**
 * Breaks the cross-context rule from a driving adapter: payroll's web adapter names notifications'
 * published port, where only a payroll driven adapter may (ADR-008).
 */
public final class WebAdapterReachingAPublishedPort {

  /** The reference the rule must reject. */
  public void notifyThrough(FixtureNotifyUseCase useCase) {
    useCase.notifySomebody();
  }
}
