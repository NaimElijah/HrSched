package io.github.naimelijah.hrsched.architecture.fixtures.configuration;

import io.github.naimelijah.hrsched.architecture.fixtures.notifications.application.port.in.FixtureNotifyUseCase;

/**
 * Keeps the composition-root rule: an ordinary class beside the wiring may use what a context
 * publishes, as any context may.
 */
public final class HelperReachingAPublishedPort {

  /** The reference the rule must permit. */
  public void notifyThrough(FixtureNotifyUseCase useCase) {
    useCase.notifySomebody();
  }
}
