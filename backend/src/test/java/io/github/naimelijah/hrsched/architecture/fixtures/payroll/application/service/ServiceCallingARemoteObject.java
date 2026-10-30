package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service;

import java.rmi.registry.Registry;

/** Breaks the I/O rule with the JDK alone: a use case holding a registry of remote objects. */
public record ServiceCallingARemoteObject(Registry registry) {}
