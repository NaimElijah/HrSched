package io.github.naimelijah.hrsched.architecture.fixtures;

import io.github.naimelijah.hrsched.architecture.fixtures.notifications.domain.model.FixtureNotice;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;

/**
 * Breaks the root-package rule: a class beside the entry point, in no context, no layer and not the
 * composition root, translating between two contexts' domains where no other rule looks.
 */
public final class RosterTranslator {

  /** The translation the rule must refuse a home in the root package. */
  public FixtureNotice noticeOf(FixturePayslip payslip) {
    return new FixtureNotice(payslip.reference());
  }
}
