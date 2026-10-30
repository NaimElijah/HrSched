package io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the application rule: a use case logging through SLF4J, which the allow-list admits so that
 * whether a use case may log stays a decision of its own.
 */
public final class ServiceLoggingThroughSlf4j {

  private static final Logger log = LoggerFactory.getLogger(ServiceLoggingThroughSlf4j.class);

  /** Logs a constant message, which the rule must permit. */
  public void pay() {
    log.info("payslip issued");
  }
}
