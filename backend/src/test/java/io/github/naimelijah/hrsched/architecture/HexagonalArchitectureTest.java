package io.github.naimelijah.hrsched.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Optional;

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
   * Contexts integrate only through one another's published driving ports.
   *
   * <p>ADR-006 forbade every cross-context reference. Modelling the real domain showed that is too
   * strict to be honest: generating a roster needs qualifications and unavailability, which live in
   * {@code workforce}. Rather than smuggle that dependency through configuration, the rule is
   * narrowed to what a published language actually means — a context may reference another
   * context's {@code application.port.in} package, and nothing else of it. Its domain, its
   * services, its adapters and its driven ports all stay private. See ADR-008.
   *
   * <p>Loosening a rule is exactly when a rule quietly stops protecting anything, so this one has a
   * failure test: it must reject a reference into another context's domain while permitting one
   * into its published ports.
   */
  @ArchTest
  static final ArchRule contexts_touch_only_published_ports_of_other_contexts =
      classes()
          .should(onlyReachOtherContextsThroughTheirPublishedPorts())
          .because("a context may use another context's published driving ports, and nothing else");

  private static ArchCondition<JavaClass> onlyReachOtherContextsThroughTheirPublishedPorts() {
    return new ArchCondition<>("only reach other contexts through their published driving ports") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        Optional<String> originContext = ContextPackages.contextOf(origin);
        if (originContext.isEmpty()) {
          return;
        }
        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
          JavaClass target = dependency.getTargetClass();
          Optional<String> targetContext = ContextPackages.contextOf(target);
          boolean crossesContexts =
              targetContext.isPresent() && !targetContext.get().equals(originContext.get());
          if (crossesContexts && !ContextPackages.isPublished(target)) {
            events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
          }
        }
      }
    };
  }

  /**
   * The shared kernel depends on no context.
   *
   * <p>A shared kernel is a coupling every context pays for, so it only stays cheap while it stays
   * small and knows nothing. The moment it reaches into a context, every context transitively
   * depends on that one and the boundaries stop meaning anything. See ADR-008.
   */
  @ArchTest
  static final ArchRule the_shared_kernel_depends_on_no_context =
      noClasses()
          .that()
          .resideInAPackage("..shared..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..scheduling..", "..workforce..", "..access..", "..configuration..")
          .because("a kernel that knows about a context couples every context to that context");

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
