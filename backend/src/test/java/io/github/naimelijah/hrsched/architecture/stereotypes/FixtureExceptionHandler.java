package io.github.naimelijah.hrsched.architecture.stereotypes;

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

  /** The packages whose controllers it translates for, named as Spring's attribute is. */
  String[] basePackages() default {};

  /**
   * Classes whose packages are those whose controllers it translates for, named as Spring's
   * attribute is.
   */
  Class<?>[] basePackageClasses() default {};
}
