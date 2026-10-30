package io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.in.web.payruns;

import io.github.naimelijah.hrsched.architecture.stereotypes.FixtureExceptionHandler;

/**
 * A payroll exception handler scoped to a package by name, as Spring's {@code basePackages} scopes
 * one: it covers every controller in that package and below, and none outside it.
 */
@FixtureExceptionHandler(
    basePackages =
        "io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.in.web.payruns")
public final class PayRunsExceptionHandler {}
