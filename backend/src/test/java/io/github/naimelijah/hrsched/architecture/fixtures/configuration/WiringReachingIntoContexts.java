package io.github.naimelijah.hrsched.architecture.fixtures.configuration;

import io.github.naimelijah.hrsched.architecture.fixtures.notifications.application.port.out.FixtureNoticeStore;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;
import io.github.naimelijah.hrsched.architecture.stereotypes.FixtureConfiguration;

/**
 * Keeps the composition-root rule: wiring, which must see inside every context to build it, and a
 * class nested in it, which is part of it.
 */
@FixtureConfiguration
public class WiringReachingIntoContexts {

  /** A reference into a domain the rule must permit. */
  public FixturePayslip payslip() {
    return new FixturePayslip("fixture");
  }

  /** A nested class of the wiring, reaching a driven port the rule must permit. */
  public static final class Nested {

    /** A reference into a driven port the rule must permit. */
    public void keepThrough(FixtureNoticeStore store) {
      store.keep();
    }
  }
}
