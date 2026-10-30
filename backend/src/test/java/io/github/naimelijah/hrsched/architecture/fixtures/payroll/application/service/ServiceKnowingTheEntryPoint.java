package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service;

import io.github.naimelijah.hrsched.HrSchedApplication;

/**
 * Breaks the application rule with an HrSched class outside the core: the application's entry
 * point, in the root package.
 */
public record ServiceKnowingTheEntryPoint(Class<HrSchedApplication> application) {}
