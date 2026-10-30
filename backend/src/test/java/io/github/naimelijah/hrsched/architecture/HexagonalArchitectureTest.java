package io.github.naimelijah.hrsched.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.OrganisationRepository;
import io.github.naimelijah.hrsched.access.application.port.out.SessionRepository;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.SessionRevocation;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.CharArrayReader;
import java.io.CharArrayWriter;
import java.io.Console;
import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.zip.ZipFile;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

  /**
   * The domain depends on the JDK and on HrSched's own domain, and on nothing else.
   *
   * <p>An allow-list rather than a list of forbidden frameworks: a list names only the libraries
   * somebody thought of, so a Jackson annotation on a domain record, or any library added later,
   * passed it. Within HrSched it allows only a {@code domain} package, the kernel's included, so
   * the root package's {@code HrSchedApplication} is outside it as much as a library is. {@code
   * HexagonalArchitectureRulesTest} watches it reject a library, Spring and that entry point. The
   * outer layers are also forbidden by name, as {@link #dependencies_point_inward} forbids them, so
   * a {@code domain} package nested inside one of them is not a way in.
   */
  @ArchTest
  static final ArchRule domain_depends_on_nothing_but_the_jdk =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.resideOutsideOfPackages(
                      "java..", "io.github.naimelijah.hrsched..domain..")
                  .or(
                      JavaClass.Predicates.resideInAnyPackage(
                          "..application..", "..adapter..", "..configuration..")))
          .because("the domain must be testable and reusable without the framework");

  /**
   * The application layer depends on the JDK and on HrSched's own core, and on nothing else.
   *
   * <p>An allow-list, as the domain's is. This rule once forbade Spring, the adapters and the
   * composition root by name, so Jackson, a token library, the PostgreSQL driver or any library
   * added later passed it; a use case needing one of those needs a driven port instead. Within
   * HrSched it allows everything but an adapter, the composition root and the root package's {@code
   * HrSchedApplication}. What it may use of another context is {@link
   * #contexts_touch_only_published_ports_of_other_contexts}'s concern. {@code
   * HexagonalArchitectureRulesTest} watches it reject a library and that entry point.
   */
  @ArchTest
  static final ArchRule application_is_free_of_the_framework =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.resideOutsideOfPackages(
                      "java..", "io.github.naimelijah.hrsched..")
                  .or(
                      JavaClass.Predicates.resideInAnyPackage(
                          "..adapter..", "..configuration..", "io.github.naimelijah.hrsched")))
          .because(
              "a use case is plain Java over its own ports, wired by configuration and never"
                  + " annotated by it");

  /**
   * The JDK packages that reach a database, a network, a remote object or a disk, whatever their
   * types are called.
   */
  private static final String[] JDK_IO_PACKAGES = {
    "java.sql..", "java.net..", "java.rmi..", "java.nio.file..", "java.nio.channels.."
  };

  /**
   * The JDK types that read or write a disk, a terminal, a stream or another program, whatever
   * package they are in. Their subtypes are refused with them.
   */
  private static final Set<Class<?>> JDK_IO_TYPES =
      Set.of(
          InputStream.class,
          OutputStream.class,
          Reader.class,
          Writer.class,
          File.class,
          RandomAccessFile.class,
          FileDescriptor.class,
          Console.class,
          ZipFile.class,
          ProcessBuilder.class,
          Process.class,
          Runtime.class);

  /**
   * The JDK types in those packages or hierarchies that reach nothing outside the process: a
   * location, which is a value, and the readers, writers and streams over memory. Exempt by exact
   * type, so a subclass of one is not.
   */
  private static final Set<Class<?>> JDK_IN_MEMORY_TYPES =
      Set.of(
          URI.class,
          StringReader.class,
          StringWriter.class,
          ByteArrayInputStream.class,
          ByteArrayOutputStream.class,
          CharArrayReader.class,
          CharArrayWriter.class);

  /**
   * The domain and the application layer do no I/O through the JDK.
   *
   * <p>Both allow-lists admit the whole JDK, and the JDK is not only collections and time. So this
   * rule refuses, in either layer, any dependency on {@code java.sql} (a database), {@code
   * java.net} (a network), {@code java.rmi} (a remote object), {@code java.nio.file} or {@code
   * java.nio.channels} (a disk or a channel), and on {@code InputStream}, {@code OutputStream},
   * {@code Reader}, {@code Writer}, {@code File}, {@code RandomAccessFile}, {@code FileDescriptor},
   * {@code Console}, {@code ZipFile}, {@code ProcessBuilder}, {@code Process} or {@code Runtime},
   * or any subtype of one. It exempts {@code java.net.URI} and the in-memory {@code StringReader},
   * {@code StringWriter}, {@code ByteArrayInputStream}, {@code ByteArrayOutputStream}, {@code
   * CharArrayReader} and {@code CharArrayWriter}. Reaching outside is a driven port's job, so the
   * use case can be tested with a fake and the domain with {@code new}. A port therefore cannot
   * declare an {@code OutputStream} parameter either, and that is intended: the adapter does the
   * I/O.
   *
   * <p>{@code java.io}'s exceptions and {@code Serializable} do no I/O and stay allowed, as do
   * {@code java.nio.charset} and {@code java.security}, which the core uses to measure a password
   * and to draw a token. A library that does I/O is the allow-lists' to refuse, not this rule's.
   * {@code HexagonalArchitectureRulesTest} watches it reject a file and an archive in the domain,
   * and an HTTP client, a process and a remote-object registry in a use case, and permit {@code
   * Serializable}, a {@code URI} and the in-memory types.
   */
  @ArchTest
  static final ArchRule the_core_does_no_io =
      noClasses()
          .that()
          .resideInAnyPackage("..domain..", "..application..")
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.resideInAnyPackage(JDK_IO_PACKAGES)
                  .or(
                      DescribedPredicate.describe(
                          "are JDK streams, readers, writers, files, archives or processes",
                          target -> JDK_IO_TYPES.stream().anyMatch(target::isAssignableTo)))
                  .and(
                      DescribedPredicate.describe(
                          "are not a URI or an in-memory reader, writer or stream",
                          target ->
                              JDK_IN_MEMORY_TYPES.stream().noneMatch(target::isEquivalentTo))))
          .because(
              "reaching a database, a network, a disk or another program is a driven port's job");

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
   * Contexts integrate only through one another's published driving ports, consumed in an adapter.
   *
   * <p>ADR-006 forbade every cross-context reference. Modelling the real domain showed that is too
   * strict to be honest: generating a roster needs qualifications and unavailability, which live in
   * {@code workforce}. Rather than smuggle that dependency through configuration, the rule is
   * narrowed to what a published language actually means — a context may reference another
   * context's {@code application.port.in} package, and nothing else of it. Its domain, its
   * services, its adapters and its driven ports all stay private. See ADR-008.
   *
   * <p>The allowance is spent only in the consuming context's {@code adapter.out}, behind a driven
   * port of its own, so its domain and application layers never name the other context and a change
   * to the other's published language stops at that adapter (ADR-008). Which adapter, and how many,
   * is left to each context.
   *
   * <p>Loosening a rule is exactly when a rule quietly stops protecting anything, so this one has a
   * failure test: {@code HexagonalArchitectureRulesTest} evaluates it against fixture contexts, and
   * it must reject a reference into another context's domain or driven ports, and a use case or a
   * driving adapter naming another's published port, while permitting a driven adapter's reference
   * into that port.
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
        .because(
            "a context may use another context's published driving ports, from a driven adapter,"
                + " and nothing else");
  }

  /**
   * Only the wiring sees inside a context: in {@code configuration}, a class annotated
   * {@code @Configuration}, and the classes nested in one.
   *
   * <p>Building a context's services means naming them, and their driven ports, so the composition
   * root cannot be held to published ports as a context is. But the exemption belongs to the
   * wiring, not to the package: exempting every class under {@code configuration} would leave a
   * sanctioned back door, where translation logic between contexts accretes in an ordinary class
   * that merely lives there, the one place no rule inspects (ADR-008). Any other class there may
   * use a context's published ports, as a context may, and nothing else of it.
   *
   * <p>The annotation is a parameter because a fixture cannot carry Spring's: see {@code
   * FixtureConfiguration}. {@code HexagonalArchitectureRulesTest} watches the rule reject a helper
   * beside the wiring reaching a domain, permit its reaching a published port, permit the wiring
   * and a class nested in it, and permit {@code UseCaseConfiguration} under the build's own rule.
   */
  @ArchTest
  static final ArchRule only_the_wiring_sees_inside_a_context =
      onlyTheWiringSeesInsideAContext(ContextPackages.HRSCHED, Configuration.class);

  /**
   * @param packages where the contexts and the composition root are; {@link
   *     ContextPackages#HRSCHED} for the build's rule
   * @param wiring the annotation that marks wiring; {@code @Configuration} for the build's rule
   * @return the composition-root rule over those packages
   */
  static ArchRule onlyTheWiringSeesInsideAContext(
      ContextPackages packages, Class<? extends Annotation> wiring) {
    return classes()
        .that(
            DescribedPredicate.describe(
                "are in the composition root", packages::isInTheCompositionRoot))
        .should(reachContextsOnlyThroughTheirPublishedPortsUnlessWiring(packages, wiring))
        .because("only the wiring builds a context, so only the wiring may see inside one");
  }

  private static ArchCondition<JavaClass> reachContextsOnlyThroughTheirPublishedPortsUnlessWiring(
      ContextPackages packages, Class<? extends Annotation> wiring) {
    return new ArchCondition<>(
        "reach contexts only through their published driving ports, unless annotated @"
            + wiring.getSimpleName()) {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        if (isWiring(origin, wiring)) {
          return;
        }
        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
          JavaClass target = dependency.getTargetClass();
          if (packages.contextOf(target).isPresent() && !packages.isPublished(target)) {
            events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
          }
        }
      }
    };
  }

  /** A nested or anonymous class of the wiring is part of it, as a lambda inside it would be. */
  private static boolean isWiring(JavaClass clazz, Class<? extends Annotation> wiring) {
    return clazz.isAnnotatedWith(wiring)
        || clazz.getEnclosingClass().map(enclosing -> isWiring(enclosing, wiring)).orElse(false);
  }

  private static ArchCondition<JavaClass> onlyReachOtherContextsThroughTheirPublishedPorts(
      ContextPackages packages) {
    return new ArchCondition<>(
        "only reach other contexts through their published driving ports, from a driven adapter") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        Optional<String> originContext = packages.contextOf(origin);
        if (originContext.isEmpty()) {
          return;
        }
        boolean mayConsume = packages.isDrivenAdapter(origin);
        for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
          JavaClass target = dependency.getTargetClass();
          Optional<String> targetContext = packages.contextOf(target);
          boolean crossesContexts =
              targetContext.isPresent() && !targetContext.get().equals(originContext.get());
          if (crossesContexts && !(mayConsume && packages.isPublished(target))) {
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
   * <p>{@link #domain_depends_on_nothing_but_the_jdk} already rejects SLF4J and Logback, as it
   * rejects every library, but not {@code java.util.logging}, which genuinely <em>is</em> the JDK.
   * That one is this rule's alone, and the other two are named here as well so that the reason
   * stays written down if the allow-list ever changes. A domain class that logs is a domain class
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
   * own by changing one package. The allow-lists of the domain and the application layer already
   * reject this library there, but nothing else would keep a model type out of any adapter but this
   * one.
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
   * <p>{@link #contexts_are_free_of_cycles} cannot see such a cycle, as both ends sit in one of its
   * slices (ADR-006). This rule can — a {@code scheduling.domain -> scheduling.application}
   * dependency fails it, and was observed doing so.
   */
  @ArchTest
  static final ArchRule layers_within_a_context_are_free_of_cycles =
      slices()
          .matching("io.github.naimelijah.hrsched.(*).(*)..")
          .namingSlices("$1.$2")
          .should()
          .beFreeOfCycles();

  /**
   * Contexts depend on one another in one direction only.
   *
   * <p>ADR-008 lets any context reach another's published ports, so two contexts can each reach the
   * other's, adapter to port, with every other rule here satisfied. That pair can no longer change,
   * or be extracted, one without the other: the boundary between them is gone. A context-level
   * cycle rule was once removed as dead, when ADR-006 forbade every cross-context reference and so
   * left a cycle nothing to be made of; the published ports gave it a failure mode back. {@code
   * HexagonalArchitectureRulesTest} watches it reject two fixture contexts reaching each other's
   * ports, and permit one reaching the other's.
   *
   * <p>Sliced by every top-level package, so a cycle through {@code configuration} or the kernel is
   * one too.
   */
  @ArchTest
  static final ArchRule contexts_are_free_of_cycles =
      contextsAreFreeOfCycles(ContextPackages.HRSCHED);

  /**
   * @param packages where the contexts are; {@link ContextPackages#HRSCHED} for the build's rule
   * @return the context cycle rule over those contexts
   */
  static ArchRule contextsAreFreeOfCycles(ContextPackages packages) {
    return slices()
        .matching(packages.topLevelPackages())
        .namingSlices("$1")
        .should()
        .beFreeOfCycles()
        .because("two contexts reaching each other can no longer change one without the other");
  }

  /**
   * The attribute of an exception handler naming the controllers whose exceptions it translates.
   */
  private static final String HANDLED_CONTROLLERS = "assignableTypes";

  /**
   * Every controller in a context is listed by an exception handler of that context.
   *
   * <p>Each context translates its own domain exceptions in a {@code @RestControllerAdvice} limited
   * by {@code assignableTypes} to its own controllers, so that one context's handler never answers
   * for another's exception. The cost of that limit is a failure nothing reports: a controller left
   * off the list compiles, starts and serves, and its domain exceptions are simply never
   * translated. {@code HexagonalArchitectureRulesTest} watches it reject a fixture controller its
   * handler does not list, and permit one it does.
   *
   * <p>The annotations are parameters for the reason {@link #onlyTheWiringSeesInsideAContext}'s is;
   * the handler's is read by the attribute name Spring gives it.
   */
  @ArchTest
  static final ArchRule every_controller_is_listed_by_its_contexts_handler =
      everyControllerIsListedByItsContextsHandler(
          ContextPackages.HRSCHED, RestController.class, RestControllerAdvice.class);

  /**
   * @param packages where the contexts are; {@link ContextPackages#HRSCHED} for the build's rule
   * @param controller the annotation that marks a controller; {@code @RestController} for the
   *     build's rule
   * @param handler the annotation that marks an exception handler, with an {@code assignableTypes}
   *     attribute; {@code @RestControllerAdvice} for the build's rule
   * @return the exception-handler rule over those contexts
   */
  static ArchRule everyControllerIsListedByItsContextsHandler(
      ContextPackages packages,
      Class<? extends Annotation> controller,
      Class<? extends Annotation> handler) {
    return classes()
        .that()
        .areAnnotatedWith(controller)
        .and(DescribedPredicate.describe("belong to a bounded context", inAContext(packages)))
        .should(beListedByAHandlerOfTheirContext(packages, handler))
        .because("a controller its handler does not list has its domain exceptions untranslated");
  }

  private static Predicate<JavaClass> inAContext(ContextPackages packages) {
    return clazz -> packages.contextOf(clazz).isPresent();
  }

  private static ArchCondition<JavaClass> beListedByAHandlerOfTheirContext(
      ContextPackages packages, Class<? extends Annotation> handler) {
    return new ArchCondition<>(
        "be listed in the " + HANDLED_CONTROLLERS + " of a @" + handler.getSimpleName()) {
      @Override
      public void check(JavaClass controller, ConditionEvents events) {
        Optional<String> context = packages.contextOf(controller);
        boolean listed =
            controller.getDirectDependenciesToSelf().stream()
                .map(Dependency::getOriginClass)
                .filter(origin -> packages.contextOf(origin).equals(context))
                .flatMap(origin -> origin.tryGetAnnotationOfType(handler.getName()).stream())
                .anyMatch(annotation -> lists(annotation, controller));
        if (!listed) {
          events.add(
              SimpleConditionEvent.violated(
                  controller,
                  controller.getName()
                      + " is in the "
                      + HANDLED_CONTROLLERS
                      + " of no @"
                      + handler.getSimpleName()
                      + " in "
                      + context.orElseThrow()));
        }
      }
    };
  }

  private static boolean lists(JavaAnnotation<?> handler, JavaClass controller) {
    return handler.get(HANDLED_CONTROLLERS).map(JavaClass[].class::cast).stream()
        .flatMap(Arrays::stream)
        .anyMatch(listed -> listed.getName().equals(controller.getName()));
  }

  /**
   * Every class in a context sits in its {@code domain}, its {@code application} or its {@code
   * adapter} package.
   *
   * <p>Every context repeats the same shape (ADR-003, ADR-006), and the rules above lean on it: the
   * domain's and the application's are written against those package names, and {@link
   * #dependencies_point_inward} considers only classes inside a layer. A class in {@code
   * scheduling.util} would escape all three, free to depend on anything and to be depended on by
   * anything. {@code HexagonalArchitectureRulesTest} watches it reject such a class, and permit a
   * class from each layer.
   */
  @ArchTest
  static final ArchRule every_class_in_a_context_is_in_a_layer =
      everyClassInAContextIsInALayer(ContextPackages.HRSCHED);

  /**
   * @param packages where the contexts are; {@link ContextPackages#HRSCHED} for the build's rule
   * @return the layer rule over those contexts
   */
  static ArchRule everyClassInAContextIsInALayer(ContextPackages packages) {
    return classes()
        .that(DescribedPredicate.describe("belong to a bounded context", inAContext(packages)))
        .should(
            new ArchCondition<>(
                "reside in their context's domain, application or adapter package") {
              @Override
              public void check(JavaClass clazz, ConditionEvents events) {
                if (!packages.isInALayer(clazz)) {
                  events.add(
                      SimpleConditionEvent.violated(
                          clazz, clazz.getName() + " is in no layer of its context"));
                }
              }
            })
        .because("a class outside every layer escapes every rule written against the layers");
  }

  /**
   * A repository method allowed to take no {@code OrganisationId}.
   *
   * <p>Named by its parameter types as well as its name, so an overload added later, or the method
   * itself once its parameters change, is not allowed by an entry written for another signature.
   *
   * @param port the driven port declaring it, by class literal so a renamed port fails compilation
   * @param method its name
   * @param parameters its parameter types, raw, in order
   * @param reason why it cannot be scoped, as its port's javadoc gives it
   */
  record UnscopedLookup(Class<?> port, String method, List<Class<?>> parameters, String reason) {}

  /**
   * The repository methods that take no organisation, each named, with the reason its port gives:
   * six reads, and the five writes that keep a session.
   *
   * <p>All belong to access, where the tenant is still being discovered: signing in and
   * authenticating come before there is an organisation, the operator belongs to none, a session
   * belongs to an account rather than a tenant, and the register of tenants is the one thing with
   * no tenant above it. ADR-055 records each.
   */
  static final List<UnscopedLookup> UNSCOPED_LOOKUPS =
      List.of(
          new UnscopedLookup(
              AccountRepository.class,
              "findBySignInName",
              List.of(SignInName.class),
              "a sign-in request carries a name and a password, and no organisation to scope by"
                  + " until the account is found"),
          new UnscopedLookup(
              AccountRepository.class,
              "findForAuthentication",
              List.of(UserId.class),
              "authentication works before there is an organisation, and the operator's account"
                  + " belongs to none"),
          new UnscopedLookup(
              AccountRepository.class,
              "findForUpdate",
              List.of(Optional.class, UserId.class),
              "scoped by an Optional<OrganisationId>, whose empty value names the operator's"
                  + " account, which belongs to no organisation"),
          new UnscopedLookup(
              SessionRepository.class,
              "findRefreshToken",
              List.of(byte[].class),
              "a session belongs to an account rather than a tenant, and is reached only by the"
                  + " digest of a token this system issued"),
          new UnscopedLookup(
              SessionRepository.class,
              "findAuthenticatedSession",
              List.of(UserId.class, SignInSessionId.class),
              "the read that discovers which organisation the caller belongs to, and the operator"
                  + " belongs to none"),
          new UnscopedLookup(
              SessionRepository.class,
              "start",
              List.of(SignInSession.class, byte[].class, Instant.class),
              "a session belongs to an account rather than a tenant, and the operator's to none,"
                  + " so there is no organisation to open it under"),
          new UnscopedLookup(
              SessionRepository.class,
              "markReplaced",
              List.of(byte[].class, Instant.class),
              "reaches a refresh token only by the digest of a token this system issued"),
          new UnscopedLookup(
              SessionRepository.class,
              "issueSuccessor",
              List.of(SignInSessionId.class, byte[].class, Instant.class, Instant.class),
              "renews a session reached only through a token this system issued, and a session"
                  + " belongs to an account rather than a tenant"),
          new UnscopedLookup(
              SessionRepository.class,
              "revoke",
              List.of(SignInSessionId.class, SessionRevocation.class, Instant.class),
              "ends a session reached only through a token this system issued, and a session"
                  + " belongs to an account rather than a tenant"),
          new UnscopedLookup(
              SessionRepository.class,
              "revokeEverySessionOf",
              List.of(UserId.class, SessionRevocation.class, Instant.class),
              "ends the sessions of one account, which is their scope, and the operator's"
                  + " account belongs to no organisation"),
          new UnscopedLookup(
              OrganisationRepository.class,
              "findAll",
              List.of(),
              "the register of tenants has no outer scope to filter by, and listing it is"
                  + " operator-only at the use case above"));

  /**
   * The method names of a save, the one kind of repository method the tenancy rule does not ask
   * about: the aggregate it stores carries its own organisation.
   */
  private static final String SAVE = "save.*";

  /**
   * Every method of a repository or a directory takes the organisation it works in, but a save.
   *
   * <p>ADR-009 makes the tenant part of the signature, so a cross-tenant read cannot be written by
   * forgetting a {@code WHERE organisation_id = ?}, only by passing the wrong organisation, which a
   * reviewer sees. That holds only while every method of a driven port named {@code …Repository} or
   * {@code …Directory} takes an {@code OrganisationId}: a {@code findById(EmployeeId)}, an {@code
   * existsByEmail(String)} or a {@code countActive()} added later would compile, pass review by
   * habit, and read across tenants or tell that a record exists in one. Every method is asked,
   * rather than those named like a lookup, because a name pattern catches only the names somebody
   * thought of. A save is exempt, as its aggregate carries its organisation. The few methods that
   * cannot take one are named in {@link #UNSCOPED_LOOKUPS}, each with its port's own reason, so a
   * new one is a decision written down rather than an omission. {@code
   * HexagonalArchitectureRulesTest} watches it reject a fixture repository's unscoped lookup and
   * unscoped question, a fixture directory's unscoped read, and an overload of an allowed method,
   * and permit a scoped repository; it checks that every allowed method is still a signature its
   * port declares, with a reason.
   */
  @ArchTest
  static final ArchRule repository_methods_take_an_organisation =
      repositoryMethodsTakeAnOrganisation(UNSCOPED_LOOKUPS);

  /**
   * @param unscoped the methods allowed to take no organisation; {@link #UNSCOPED_LOOKUPS} for the
   *     build's rule
   * @return the tenancy rule with that allow-list
   */
  static ArchRule repositoryMethodsTakeAnOrganisation(List<UnscopedLookup> unscoped) {
    return methods()
        .that()
        .areDeclaredInClassesThat(
            JavaClass.Predicates.resideInAPackage("..application.port.out..")
                .and(
                    JavaClass.Predicates.simpleNameEndingWith("Repository")
                        .or(JavaClass.Predicates.simpleNameEndingWith("Directory")))
                .and(JavaClass.Predicates.INTERFACES))
        .and()
        .haveNameNotMatching(SAVE)
        .and(DescribedPredicate.not(oneOf(unscoped)))
        .should(takeAnOrganisation())
        .because(
            "a method without the tenant in its signature is a cross-tenant read waiting to be"
                + " written (ADR-009)");
  }

  private static DescribedPredicate<JavaMethod> oneOf(List<UnscopedLookup> lookups) {
    return DescribedPredicate.describe(
        "are an allowed unscoped method",
        method ->
            lookups.stream()
                .anyMatch(
                    lookup ->
                        method.getOwner().isEquivalentTo(lookup.port())
                            && method.getName().equals(lookup.method())
                            && hasParameters(method, lookup.parameters())));
  }

  private static boolean hasParameters(JavaMethod method, List<Class<?>> parameters) {
    List<JavaClass> actual = method.getRawParameterTypes();
    return actual.size() == parameters.size()
        && IntStream.range(0, parameters.size())
            .allMatch(index -> actual.get(index).isEquivalentTo(parameters.get(index)));
  }

  private static ArchCondition<JavaMethod> takeAnOrganisation() {
    return new ArchCondition<>("take an " + OrganisationId.class.getSimpleName()) {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean scoped =
            method.getRawParameterTypes().stream()
                .anyMatch(parameter -> parameter.isEquivalentTo(OrganisationId.class));
        if (!scoped) {
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  method.getFullName() + " takes no " + OrganisationId.class.getSimpleName()));
        }
      }
    };
  }
}
