/**
 * Classes that break the architecture rules on purpose, so {@code HexagonalArchitectureRulesTest}
 * can watch each rule reject them. Nothing here is production code, and nothing here may be.
 *
 * <p>This package stands in for HrSched's root package, and each subpackage for one kind of package
 * the rules reason about: {@code notifications} and {@code payroll} for two bounded contexts
 * (payroll is invented, a context no rule names), {@code shared} for the kernel and {@code
 * configuration} for the composition root. In the package itself, {@code FixtureApplication} stands
 * in for the entry point and {@code RosterTranslator} is the class beside it the root-package rule
 * refuses. They live in the test sources, which {@code HexagonalArchitectureTest} does not import,
 * so they can never fail or satisfy the rules the build enforces.
 *
 * <p>There is no other subpackage, because {@code ContextPackages} rooted here reads every other
 * one as a bounded context. So the stand-in annotations live in {@code architecture.stereotypes},
 * and the fixtures of the actor rules, which no context root decides, in {@code
 * architecture.actors}.
 *
 * <p>No fixture carries a Spring stereotype or {@code @ConfigurationProperties}, as this package is
 * inside the application's component-scan and configuration-properties-scan base. The rules that
 * look for a stereotype take it as a parameter, and a fixture carries a stand-in.
 */
package io.github.naimelijah.hrsched.architecture.fixtures;
