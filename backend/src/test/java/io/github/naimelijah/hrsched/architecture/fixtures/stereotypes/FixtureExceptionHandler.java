package io.github.naimelijah.hrsched.architecture.fixtures.stereotypes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stands in for Spring's {@code @RestControllerAdvice} on a fixture exception handler, for the
 * reason {@link FixtureConfiguration} gives.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FixtureExceptionHandler {

  /**
   * The controllers whose exceptions it translates, named as Spring's attribute is, since the rule
   * reads the attribute by that name.
   */
  Class<?>[] assignableTypes() default {};
}
