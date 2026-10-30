package io.github.naimelijah.hrsched.architecture.stereotypes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stands in for Spring's {@code @RestController} on a fixture controller, for the reason {@link
 * FixtureConfiguration} gives.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FixtureController {}
