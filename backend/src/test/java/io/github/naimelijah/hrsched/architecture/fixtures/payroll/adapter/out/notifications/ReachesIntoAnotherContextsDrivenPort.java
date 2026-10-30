package io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.out.notifications;

import io.github.naimelijah.hrsched.architecture.fixtures.notifications.application.port.out.FixtureNoticeStore;

/** Breaks the cross-context rule: payroll references a driven port notifications keeps private. */
public final class ReachesIntoAnotherContextsDrivenPort {

  /** The reference the rule must reject. */
  public void keepThrough(FixtureNoticeStore store) {
    store.keep();
  }
}
