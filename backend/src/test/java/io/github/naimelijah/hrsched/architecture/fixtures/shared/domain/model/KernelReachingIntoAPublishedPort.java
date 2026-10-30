package io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model;

import io.github.naimelijah.hrsched.architecture.fixtures.notifications.application.port.in.FixtureNotifyUseCase;

/** Breaks the kernel rule: a published port is still a context the kernel may not know. */
public record KernelReachingIntoAPublishedPort(FixtureNotifyUseCase useCase) {}
