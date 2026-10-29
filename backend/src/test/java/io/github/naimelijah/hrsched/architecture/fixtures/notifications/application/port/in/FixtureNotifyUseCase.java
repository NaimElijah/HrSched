package io.github.naimelijah.hrsched.architecture.fixtures.notifications.application.port.in;

/** A driving port of the fixture {@code notifications} context: the part it publishes. */
public interface FixtureNotifyUseCase {

  /** Sends a notice. */
  void notifySomebody();
}
