package io.github.naimelijah.hrsched.architecture.stereotypes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stands in for Spring's {@code @Configuration} on fixture wiring.
 *
 * <p>The fixtures sit under {@code @SpringBootApplication}'s component-scan base, and the shared
 * test context would pick up, and try to proxy, a fixture carrying the real one. The rule takes the
 * annotation as a parameter, so a fixture test passes this one where the build passes Spring's.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FixtureConfiguration {}
