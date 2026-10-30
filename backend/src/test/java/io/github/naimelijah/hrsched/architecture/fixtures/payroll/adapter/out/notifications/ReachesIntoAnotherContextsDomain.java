package io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.out.notifications;

import io.github.naimelijah.hrsched.architecture.fixtures.notifications.domain.model.FixtureNotice;

/** Breaks the cross-context rule: payroll references notifications' domain. */
public final class ReachesIntoAnotherContextsDomain {

  /** The reference the rule must reject. */
  public FixtureNotice notice() {
    return new FixtureNotice("fixture");
  }
}
