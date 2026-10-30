package io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model;

import io.github.naimelijah.hrsched.architecture.fixtures.notifications.domain.model.FixtureNotice;

/** Breaks the kernel rule by knowing the notifications context, the one #220 found uncovered. */
public record KernelReachingIntoNotifications(FixtureNotice notice) {}
