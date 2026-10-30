package io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model;

import java.io.File;

/** Breaks the I/O rule with the JDK alone: a domain value that knows where it is stored. */
public record DomainReadingAFile(File source) {}
