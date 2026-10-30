package io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model;

import io.github.naimelijah.hrsched.architecture.fixtures.configuration.FixtureWiring;

/** Breaks the kernel rule by knowing the composition root. */
public record KernelReachingIntoConfiguration(FixtureWiring wiring) {}
