/**
 * Classes that break the architecture rules on purpose, so {@code HexagonalArchitectureRulesTest}
 * can watch each rule reject them. Nothing here is production code, and nothing here may be.
 *
 * <p>Each subpackage stands in for one kind of package the rules reason about: {@code
 * notifications} and {@code payroll} for two bounded contexts (payroll is invented, a context no
 * rule names), {@code shared} for the kernel and {@code configuration} for the composition root.
 * They live in the test sources, which {@code HexagonalArchitectureTest} does not import, so they
 * can never fail or satisfy the rules the build enforces.
 *
 * <p>No fixture carries a Spring stereotype, as this package is inside the application's
 * component-scan base. {@code stereotypes} holds the stand-ins, for the rules that take the
 * annotation they look for as a parameter.
 */
package io.github.naimelijah.hrsched.architecture.fixtures;
