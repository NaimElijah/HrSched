package io.github.naimelijah.hrsched.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
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
   * Nothing prints.
   *
   * <p>Logs are structured JSON on stdout (ADR-016), and a {@code System.out.println} or a stray
   * {@code printStackTrace} writes a line into that stream that no encoder produced and no log
   * store can parse. It is also how a debugging aid becomes permanent: a print is invisible in
   * review and harmless in a test, and the repository's own conventions ban it in both languages.
   *
   * <p>ArchUnit's own rule, which additionally catches {@code Throwable.printStackTrace}.
   */
  @ArchTest
  static final ArchRule nothing_writes_to_the_standard_streams =
      NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  /**
   * The domain does not log.
   *
   * <p>{@link #domain_depends_on_nothing_but_the_jdk} does not cover this, and that is not an
   * oversight to leave alone: SLF4J is not Spring, so a domain logger passes it, and {@code
   * java.util.logging} genuinely <em>is</em> the JDK. A domain class that logs is a domain class
   * with a side effect, which is the property that makes the rest of it worth testing without a
   * framework.
   *
   * <p>What the domain has to say, it says by returning a value or throwing.
   */
  @ArchTest
  static final ArchRule the_domain_does_not_log =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.slf4j..", "ch.qos.logback..", "java.util.logging..")
          .because("a domain class that logs is a domain class with a side effect");

  /**
   * Instrumentation stays in the adapters.
   *
   * <p>Metrics and traces are infrastructure: they need a registry, a backend and a wire format,
   * and none of that belongs inward of a port. The application layer records what it needs recorded
   * through a driven port instead — see {@code AuditTrail} and ADR-018 — which is also what makes
   * "was this recorded?" a test rather than a reviewer's observation.
   *
   * <p>The practical consequence is that Micrometer's {@code @Timed} and {@code @Counted}, and any
   * OpenTelemetry annotation, cannot be put on a use case. That is deliberate.
   */
  @ArchTest
  static final ArchRule instrumentation_stays_in_the_adapters =
      noClasses()
          .that()
          .resideInAnyPackage("..domain..", "..application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.micrometer..", "io.opentelemetry..")
          .because("a meter or a span needs a backend, and the hexagon may not know it has one");

  /**
   * Who is acting arrives as an argument, and is never fetched from the air.
   *
   * <p>Spring Security keeps the authenticated caller in a thread-local, and reading it is one line
   * from anywhere. Every use case here takes an {@code Actor} parameter instead, and that is not
   * ceremony: a method whose identity is a parameter can be tested as anybody, reviewed by reading
   * its signature, and cannot behave differently depending on which thread it happens to be running
   * on. One that reaches for the holder can do none of those, and the case it silently gets wrong
   * is the one that matters — work moved onto another thread, where the holder is empty and the
   * call either fails far from its cause or proceeds as nobody.
   *
   * <p>It covers every package but {@code configuration.security}, and that exemption is the one
   * place where the ambient read is not a choice. {@code ProblemAccessDeniedHandler} implements a
   * Spring callback whose signature offers no {@code Authentication}, and it asks a different
   * question anyway: not "who is acting" but "who was just refused", which it needs in order to say
   * whether the refusal was a pending password change. Everywhere a parameter exists, the parameter
   * is used — a controller receives the caller through {@code @AuthenticationPrincipal}, and the
   * class that turns a verified token into an {@code Actor} is handed the token.
   *
   * <p>Watched failing twice: once on the pre-existing read in {@code ProblemAccessDeniedHandler},
   * which is why that exemption is written down rather than assumed, and once on a deliberate
   * {@code SecurityContextHolder.getContext()} in {@code AccountController}, which was reverted.
   */
  @ArchTest
  static final ArchRule the_acting_identity_is_never_read_from_the_air =
      noClasses()
          .that()
          .resideOutsideOfPackage("..configuration.security..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.security.core.context.SecurityContextHolder")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName(
              "org.springframework.security.core.context.SecurityContextHolderStrategy")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName(
              "org.springframework.security.core.context.ReactiveSecurityContextHolder")
          .because("who is acting is an argument, not an ambient fact about the current thread");

  /**
   * Cycles are checked one level below the context, between a context's own layers.
   *
   * <p>A context-level cycle rule was removed as dead: any cycle between two contexts requires a
   * dependency between them, which {@link #contexts_touch_only_published_ports_of_other_contexts}
   * already forbids outright, so it had no failure mode left. This rule does have one — a {@code
   * scheduling.domain -> scheduling.application} dependency fails it, and was observed doing so.
   */
  @ArchTest
  static final ArchRule layers_within_a_context_are_free_of_cycles =
      slices()
          .matching("io.github.naimelijah.hrsched.(*).(*)..")
          .namingSlices("$1.$2")
          .should()
          .beFreeOfCycles();
}
