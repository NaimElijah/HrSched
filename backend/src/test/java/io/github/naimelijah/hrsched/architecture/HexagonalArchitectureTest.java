package io.github.naimelijah.hrsched.architecture;

import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.context.annotation.Configuration;

/**
 * Executable specification of the hexagon.
 *
 * <p>These rules are the enforcement mechanism chosen in ADR-003. Without them the dependency
 * direction is a convention in a document, and conventions in documents are violated by accident
 * within weeks.
 */
@AnalyzeClasses(
    packages = "io.github.naimelijah.hrsched",
    importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

  @ArchTest
  static final ArchRule domain_depends_on_nothing_but_the_jdk =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "..application..",
              "..adapter..",
              "..configuration..")
          .because("the domain must be testable and reusable without the framework");

  @ArchTest
  static final ArchRule application_is_free_of_the_framework =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "..adapter..", "..configuration..")
          .because("use cases are wired by configuration, never annotated by it");

  @ArchTest
  static final ArchRule dependencies_point_inward =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Domain")
          .definedBy("..domain..")
          .layer("Application")
          .definedBy("..application..")
          .layer("Adapter")
          .definedBy("..adapter..")
          .layer("Configuration")
          .definedBy("..configuration..")
          .whereLayer("Adapter")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Configuration")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Application")
          .mayOnlyBeAccessedByLayers("Adapter", "Configuration")
          .whereLayer("Domain")
          .mayOnlyBeAccessedByLayers("Application", "Adapter", "Configuration");

  /**
   * Bounded contexts are independent. See ADR-006.
   *
   * <p>The exemption is deliberately narrow: only classes actually annotated {@code @Configuration}
   * may cross contexts, because wiring every context is precisely their job. Exempting the whole
   * {@code configuration} package would leave a sanctioned back door for cross-context logic hidden
   * in an ordinary class that happens to live there.
   */
  @ArchTest
  static final ArchRule contexts_do_not_depend_on_each_other =
      slices()
          .matching("io.github.naimelijah.hrsched.(*)..")
          .namingSlices("$1")
          .should()
          .notDependOnEachOther()
          .ignoreDependency(
              annotatedWith(Configuration.class), DescribedPredicate.<JavaClass>alwaysTrue())
          .because("a bounded context that reaches into another is no longer a boundary");

  /**
   * Cycles are checked one level below the context, between a context's own layers.
   *
   * <p>A context-level cycle rule was removed as dead: any cycle between two contexts requires a
   * dependency between them, which {@link #contexts_do_not_depend_on_each_other} already forbids
   * outright, so it had no failure mode left. This rule does have one — a {@code scheduling.domain
   * -> scheduling.application} dependency fails it, and was observed doing so.
   */
  @ArchTest
  static final ArchRule layers_within_a_context_are_free_of_cycles =
      slices()
          .matching("io.github.naimelijah.hrsched.(*).(*)..")
          .namingSlices("$1.$2")
          .should()
          .beFreeOfCycles();
}
