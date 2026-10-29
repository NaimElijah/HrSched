/**
 * Classes that break the architecture rules on purpose, so {@code HexagonalArchitectureRulesTest}
 * can watch each rule reject them. Nothing here is production code, and nothing here may be.
 *
 * <p>Each subpackage stands in for one kind of package the rules reason about: {@code
 * notifications} and {@code payroll} for two bounded contexts (payroll is invented, a context no
 * rule names), {@code shared} for the kernel and {@code configuration} for the composition root.
 * They live in the test sources, which {@code HexagonalArchitectureTest} does not import, so they
 * can never fail or satisfy the rules the build enforces.
 */
package io.github.naimelijah.hrsched.architecture.fixtures;
