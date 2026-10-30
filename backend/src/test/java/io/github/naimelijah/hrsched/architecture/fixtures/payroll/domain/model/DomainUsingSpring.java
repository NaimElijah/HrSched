package io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model;

import org.springframework.util.Assert;

/** Breaks the domain rule by calling into Spring. */
public record DomainUsingSpring(String reference) {

  /** Validates through Spring rather than the JDK, which the rule must reject. */
  public DomainUsingSpring {
    Assert.hasText(reference, "reference must not be blank");
  }
}
