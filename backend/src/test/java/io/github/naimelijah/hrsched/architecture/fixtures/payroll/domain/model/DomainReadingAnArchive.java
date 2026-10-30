package io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model;

import java.util.zip.ZipFile;

/**
 * Breaks the I/O rule with the JDK alone: a domain value holding an open archive, which reads a
 * disk from a package that sounds like collections.
 */
public record DomainReadingAnArchive(ZipFile archive) {}
