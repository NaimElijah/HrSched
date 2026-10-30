package io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model;

import java.io.BufferedReader;

/**
 * Breaks the I/O rule with a type the rule does not name: a subtype of {@code Reader}, which reads
 * whatever it wraps.
 */
public record DomainHoldingABufferedReader(BufferedReader source) {}
