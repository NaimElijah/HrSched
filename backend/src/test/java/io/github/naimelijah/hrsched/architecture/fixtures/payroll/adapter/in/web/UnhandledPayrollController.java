package io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.in.web;

import io.github.naimelijah.hrsched.architecture.stereotypes.FixtureController;

/**
 * Breaks the exception-handler rule: a controller added after the handler, which nobody added to
 * it.
 */
@FixtureController
public final class UnhandledPayrollController {}
