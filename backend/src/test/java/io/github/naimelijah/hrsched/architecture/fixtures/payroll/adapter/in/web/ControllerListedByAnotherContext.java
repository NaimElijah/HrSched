package io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.in.web;

import io.github.naimelijah.hrsched.architecture.fixtures.stereotypes.FixtureController;

/**
 * Breaks the exception-handler rule: a payroll controller that only notifications' handler lists,
 * which translates notifications' exceptions and none of payroll's.
 */
@FixtureController
public final class ControllerListedByAnotherContext {}
