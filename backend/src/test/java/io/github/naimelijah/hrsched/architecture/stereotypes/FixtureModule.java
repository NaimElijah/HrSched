package io.github.naimelijah.hrsched.architecture.stereotypes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stands in for an annotation a context's root package may carry, such as JSpecify's
 * {@code @NullMarked} or Spring Modulith's {@code @ApplicationModule}, so a fixture context has an
 * annotated {@code package-info} without depending on either.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PACKAGE)
public @interface FixtureModule {}
