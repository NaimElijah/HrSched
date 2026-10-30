package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service;

import io.github.naimelijah.hrsched.architecture.fixtures.notifications.application.port.in.FixtureNotifyUseCase;

/**
 * Breaks the cross-context rule from the core: payroll's use case names notifications' published
 * port itself, where only a payroll adapter may (ADR-008).
 */
public final class ServiceReachingAPublishedPort {

  /** The reference the rule must reject. */
  public void notifyThrough(FixtureNotifyUseCase useCase) {
    useCase.notifySomebody();
  }
}
