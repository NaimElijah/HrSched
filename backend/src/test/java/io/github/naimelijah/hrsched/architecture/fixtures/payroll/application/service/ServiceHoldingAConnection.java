package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service;

import java.sql.Connection;

/** Breaks the I/O rule with the JDK alone: a use case holding a database connection. */
public record ServiceHoldingAConnection(Connection connection) {}
