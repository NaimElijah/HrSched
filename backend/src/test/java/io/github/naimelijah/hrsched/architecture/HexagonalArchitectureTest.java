package io.github.naimelijah.hrsched.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import java.util.Optional;
import java.util.Set;

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

  /** The name of {@code Account.actor()}, which ArchUnit matches by name rather than by literal. */
  private static final String ACTOR_METHOD = "actor";

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
   * failure test: {@code HexagonalArchitectureRulesTest} evaluates it against fixture contexts, and
   * it must reject a reference into another context's domain or driven ports while permitting one
   * into its published ports.
   */
  @ArchTest
  static final ArchRule contexts_touch_only_published_ports_of_other_contexts =
      contextsTouchOnlyPublishedPortsOfOtherContexts(ContextPackages.HRSCHED);

  /**
   * @param packages where the contexts are; {@link ContextPackages#HRSCHED} for the build's rule
   * @return the cross-context rule over those contexts
   */
  static ArchRule contextsTouchOnlyPublishedPortsOfOtherContexts(ContextPackages packages) {
    return classes()
        .should(onlyReachOtherContextsThroughTheirPublishedPorts(packages))
        .because("a context may use another context's published driving ports, and nothing else");
  }

  private static ArchCondition<JavaClass> onlyReachOtherContextsThroughTheirPublishedPorts(
      ContextPackages packages) {
    return new ArchCondition<>("only reach other contexts through their published driving ports") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        Optional<String> originContext = packages.contextOf(origin);
        if (originContext.isEmpty()) {
          return;
        }
        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
          JavaClass target = dependency.getTargetClass();
          Optional<String> targetContext = packages.contextOf(target);
          boolean crossesContexts =
              targetContext.isPresent() && !targetContext.get().equals(originContext.get());
          if (crossesContexts && !packages.isPublished(target)) {
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
   *
   * <p>What counts as a context comes from {@link ContextPackages#contextOf}, the definition the
   * cross-context rule uses, rather than from a list of names: a hand-kept list left out {@code
   * notifications} when that context arrived (#220), and would leave out the next one too. {@code
   * HexagonalArchitectureRulesTest} watches it reject a kernel class reaching into {@code
   * notifications}, into a context no list names, and into configuration.
   *
   * <p>In this tree such a reference is also a cycle, as every context depends on the kernel, and
   * {@link #layers_within_a_context_are_free_of_cycles} reports it too. This rule is the one that
   * says why, and the one that still holds for a context that does not use the kernel.
   */
  @ArchTest
  static final ArchRule the_shared_kernel_depends_on_no_context =
      theSharedKernelDependsOnNoContext(ContextPackages.HRSCHED);

  /**
   * @param packages where the contexts are; {@link ContextPackages#HRSCHED} for the build's rule
   * @return the kernel rule over those contexts
   */
  static ArchRule theSharedKernelDependsOnNoContext(ContextPackages packages) {
    return noClasses()
        .that()
        .resideInAPackage("..shared..")
        .should()
        .dependOnClassesThat(aContextOrTheCompositionRoot(packages))
        .because("a kernel that knows about a context couples every context to that context");
  }

  private static DescribedPredicate<JavaClass> aContextOrTheCompositionRoot(
      ContextPackages packages) {
    return DescribedPredicate.<JavaClass>describe(
            "belong to a bounded context", target -> packages.contextOf(target).isPresent())
        .or(JavaClass.Predicates.resideInAPackage("..configuration.."));
  }

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
   * The solver stays in the one adapter that drives it (ADR-044).
   *
   * <p>OR-Tools is native code with a modelling discipline of its own, a version held back for one
   * platform's runtime, and a crash that takes the JVM with it. Confined to {@code
   * scheduling.adapter.out.generation}, it can be replaced, upgraded or moved into a process of its
   * own by changing one package. The rules that keep frameworks out of the domain and the
   * application name packages; none of them names this library, so without this rule a model type
   * could reach a use case unnoticed.
   */
  @ArchTest
  static final ArchRule the_solver_stays_in_its_adapter =
      noClasses()
          .that()
          .resideOutsideOfPackage("..scheduling.adapter.out.generation..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.google.ortools..")
          .because("CP-SAT is infrastructure behind the RosterGenerator port (ADR-044)");

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
   * Only an account makes an actor.
   *
   * <p>Who is acting comes from the access token and nothing else (ADR-031): a verified token names
   * an account, and {@code Account.actor()} is the one place that account becomes a {@code
   * TenantActor} or the {@code Operator}. The kernel's records have public constructors, because a
   * record's canonical constructor is as public as the record, so without this rule an adapter or a
   * service could build whoever it liked to act as and every use case would believe it.
   *
   * <p>Matched as an access rather than a call, so a constructor reference such as {@code
   * Operator::new} is caught as well as {@code new Operator(id)}: a reference is not a call, and a
   * rule on calls alone missed it. Watched failing on both, each from a throwaway class in an
   * adapter, then deleted.
   *
   * <p>Tests are not analysed, so a test may still build any actor it needs. A record's own
   * delegating constructor is not a second author, which is why an access from the actor class
   * itself is exempt. The types are named by class literal rather than by string, so moving or
   * renaming one fails compilation instead of leaving a rule that matches nothing.
   */
  @ArchTest
  static final ArchRule only_an_account_makes_an_actor =
      noClasses()
          .that()
          .resideOutsideOfPackage("..access.domain.model..")
          .should()
          .accessTargetWhere(anActorBuiltOutsideItsOwnClass())
          .because("who is acting comes from the account a verified token names (ADR-031)");

  private static DescribedPredicate<JavaAccess<?>> anActorBuiltOutsideItsOwnClass() {
    Set<String> actors = Set.of(TenantActor.class.getName(), Operator.class.getName());
    return DescribedPredicate.describe(
        "a constructor of TenantActor or Operator, called or referenced from outside that class",
        access ->
            JavaConstructor.CONSTRUCTOR_NAME.equals(access.getName())
                && actors.contains(access.getTargetOwner().getName())
                && !access.getOriginOwner().equals(access.getTargetOwner()));
  }

  /**
   * Only the access use cases ask an account for its actor.
   *
   * <p>{@code Account}, and the memberships it is built from, have public constructors, so the rule
   * above alone would still let any class that can reach {@code access.domain.model} build an
   * account with whatever membership it liked and ask it for an actor. The use case that asks
   * today, {@code SessionService.findActor}, does so only once the account a verified token names
   * is found active with a live session. It lives in {@code access.application.service}, and only
   * that package may ask. Matched as an access, so {@code Account::actor} is caught as well as a
   * call. Watched failing on a throwaway adapter class calling it, then deleted.
   */
  @ArchTest
  static final ArchRule only_the_access_use_cases_ask_an_account_for_its_actor =
      noClasses()
          .that()
          .resideOutsideOfPackage("..access.application.service..")
          .should()
          .accessTargetWhere(anAccountAskedForItsActor())
          .because("an account becomes an actor only once a use case has verified it (ADR-031)");

  private static DescribedPredicate<JavaAccess<?>> anAccountAskedForItsActor() {
    return DescribedPredicate.describe(
        "Account.actor(), called or referenced",
        access ->
            access.getTargetOwner().isEquivalentTo(Account.class)
                && ACTOR_METHOD.equals(access.getName()));
  }

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
