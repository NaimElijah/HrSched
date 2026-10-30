package io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model;

import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;

/** Breaks the kernel rule by knowing payroll, a context no list of names would carry. */
public record KernelReachingIntoAnUnlistedContext(FixturePayslip payslip) {}
