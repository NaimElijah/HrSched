package io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.in.web.payslips;

import io.github.naimelijah.hrsched.architecture.fixtures.stereotypes.FixtureExceptionHandler;

/**
 * A payroll exception handler scoped to a package by a class in it, as Spring's {@code
 * basePackageClasses} scopes one: it covers every controller in that package and below.
 */
@FixtureExceptionHandler(basePackageClasses = PayslipController.class)
public final class PayslipsExceptionHandler {}
