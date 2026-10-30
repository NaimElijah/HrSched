package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service;

/**
 * Breaks the I/O rule with the JDK alone: a use case that can start another program, which reaches
 * the network or the disk through whatever that program does.
 */
public record ServiceStartingAProcess(ProcessBuilder builder, Process process, Runtime runtime) {}
