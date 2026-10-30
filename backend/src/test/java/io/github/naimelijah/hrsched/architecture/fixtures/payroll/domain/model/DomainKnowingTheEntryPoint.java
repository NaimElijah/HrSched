package io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model;

import io.github.naimelijah.hrsched.HrSchedApplication;

/**
 * Breaks the domain rule with an HrSched class that is no domain's: the application's entry point,
 * which sits in the root package, outside every layer the rule could name.
 */
public record DomainKnowingTheEntryPoint(Class<HrSchedApplication> application) {}
