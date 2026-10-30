package io.github.naimelijah.hrsched.architecture.fixtures.notifications.adapter.in.web;

import io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.in.web.ControllerListedByAnotherContext;
import io.github.naimelijah.hrsched.architecture.stereotypes.FixtureExceptionHandler;

/** Notifications' exception handler, listing a controller of payroll's by mistake. */
@FixtureExceptionHandler(assignableTypes = ControllerListedByAnotherContext.class)
public final class NotificationsExceptionHandler {}
