package io.github.naimelijah.hrsched.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.naimelijah.hrsched.HrSchedApplication;
import io.github.naimelijah.hrsched.access.application.service.SessionService;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.architecture.HexagonalArchitectureTest.UnscopedLookup;
import io.github.naimelijah.hrsched.architecture.fixtures.actors.AsksAnAccountForItsActor;
import io.github.naimelijah.hrsched.architecture.fixtures.actors.BuildsATenantActor;
import io.github.naimelijah.hrsched.architecture.fixtures.actors.BuildsAnOperator;
import io.github.naimelijah.hrsched.architecture.fixtures.actors.ReferencesAccountActor;
import io.github.naimelijah.hrsched.architecture.fixtures.actors.ReferencesTheOperatorConstructor;
import io.github.naimelijah.hrsched.architecture.fixtures.configuration.HelperReachingAPublishedPort;
import io.github.naimelijah.hrsched.architecture.fixtures.configuration.HelperReachingIntoADomain;
import io.github.naimelijah.hrsched.architecture.fixtures.configuration.WiringReachingIntoContexts;
import io.github.naimelijah.hrsched.architecture.fixtures.notifications.adapter.out.payroll.ReachesBackThroughPayrollsPort;
import io.github.naimelijah.hrsched.architecture.fixtures.notifications.application.port.in.FixtureNotifyUseCase;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.in.web.PayrollController;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.in.web.PayrollExceptionHandler;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.in.web.UnhandledPayrollController;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.out.notifications.ReachesIntoAnotherContextsDomain;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.out.notifications.ReachesIntoAnotherContextsDrivenPort;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.out.notifications.ReachesThroughThePublishedPort;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.in.FixturePayUseCase;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.out.ScopedPayslipRepository;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.out.UnscopedPayeeDirectory;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.port.out.UnscopedPayslipRepository;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service.FixturePayrollService;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service.ServiceCallingARemoteObject;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service.ServiceCallingOverHttp;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service.ServiceKnowingTheEntryPoint;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service.ServiceReachingAPublishedPort;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service.ServiceStartingAProcess;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.application.service.ServiceWithAWireFormat;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.DomainKnowingTheEntryPoint;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.DomainReadingAFile;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.DomainReadingAnArchive;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.DomainUsingInMemoryText;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.DomainUsingSpring;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.DomainWithAWireFormat;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.SerializablePayslip;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.util.PayslipFormatting;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.FixtureKernelValue;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.KernelReachingIntoAPublishedPort;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.KernelReachingIntoAnUnlistedContext;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.KernelReachingIntoConfiguration;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.KernelReachingIntoNotifications;
import io.github.naimelijah.hrsched.architecture.fixtures.stereotypes.FixtureConfiguration;
import io.github.naimelijah.hrsched.architecture.fixtures.stereotypes.FixtureController;
import io.github.naimelijah.hrsched.architecture.fixtures.stereotypes.FixtureExceptionHandler;
import io.github.naimelijah.hrsched.configuration.UseCaseConfiguration;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * The failure tests of {@link HexagonalArchitectureTest}: each rule whose logic is ours, rather
 * than ArchUnit's, evaluated against classes that break it on purpose.
 *
 * <p>A rule that is only ever run against the production tree has only ever been seen passing,
 * which is also what a rule that matches nothing does. So each one is evaluated here, as the very
 * rule object the build enforces or built by the same factory, against fixture classes under {@code
 * architecture.fixtures}: once on a deliberate violation, which it must report and name, and once
 * on the nearest legal case, which it must let through. The fixture contexts are rooted at that
 * package, so {@code ContextPackages} reads {@code notifications} and {@code payroll} there as two
 * contexts without either being a real one.
 */
class HexagonalArchitectureRulesTest {

  private static final String FIXTURE_ROOT = "io.github.naimelijah.hrsched.architecture.fixtures";

  private static final ContextPackages FIXTURES = new ContextPackages(FIXTURE_ROOT);

  @Test
  void
      GivenAContextReferencingAnotherContextsDomain_WhenTheCrossContextRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.contextsTouchOnlyPublishedPortsOfOtherContexts(FIXTURES),
            ReachesIntoAnotherContextsDomain.class);

    assertViolatedNaming(result, "FixtureNotice");
  }

  @Test
  void
      GivenAContextReferencingAnotherContextsDrivenPort_WhenTheCrossContextRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.contextsTouchOnlyPublishedPortsOfOtherContexts(FIXTURES),
            ReachesIntoAnotherContextsDrivenPort.class);

    assertViolatedNaming(result, "FixtureNoticeStore");
  }

  /**
   * The published port is the right target, but a use case is the wrong origin: consuming another
   * context belongs in an adapter, so the core never names it (ADR-008).
   */
  @Test
  void
      GivenAUseCaseReferencingAnotherContextsPublishedPort_WhenTheCrossContextRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.contextsTouchOnlyPublishedPortsOfOtherContexts(FIXTURES),
            ServiceReachingAPublishedPort.class);

    assertViolatedNaming(result, "FixtureNotifyUseCase");
  }

  @Test
  void
      GivenAContextReferencingAPublishedPortItsOwnDomainAndTheKernel_WhenTheCrossContextRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.contextsTouchOnlyPublishedPortsOfOtherContexts(FIXTURES),
            ReachesThroughThePublishedPort.class);

    assertHolds(result);
  }

  /**
   * Each reference is legal alone, as the cross-context rule permits a published port; together
   * they make two contexts that can no longer change, or be extracted, one without the other.
   */
  @Test
  void
      GivenTwoContextsEachReachingTheOthersPublishedPort_WhenTheContextCycleRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.contextsAreFreeOfCycles(FIXTURES),
            ReachesThroughThePublishedPort.class,
            FixtureNotifyUseCase.class,
            ReachesBackThroughPayrollsPort.class,
            FixturePayUseCase.class);

    assertViolatedNaming(result, "ReachesBackThroughPayrollsPort");
  }

  @Test
  void
      GivenOneContextReachingAnothersPublishedPort_WhenTheContextCycleRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.contextsAreFreeOfCycles(FIXTURES),
            ReachesThroughThePublishedPort.class,
            FixtureNotifyUseCase.class,
            FixturePayUseCase.class);

    assertHolds(result);
  }

  /** Evaluated with the annotation the build passes, which this helper does not carry. */
  @Test
  void
      GivenACompositionRootClassThatIsNotWiringReachingADomain_WhenTheCompositionRootRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.onlyTheWiringSeesInsideAContext(
                FIXTURES, Configuration.class),
            HelperReachingIntoADomain.class);

    assertViolatedNaming(result, "FixturePayslip");
  }

  @Test
  void
      GivenACompositionRootClassThatIsNotWiringReachingAPublishedPort_WhenTheCompositionRootRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.onlyTheWiringSeesInsideAContext(
                FIXTURES, Configuration.class),
            HelperReachingAPublishedPort.class);

    assertHolds(result);
  }

  /** The stand-in annotation, for the reason {@link FixtureConfiguration} gives. */
  @Test
  void
      GivenWiringAndAClassNestedInItReachingIntoContexts_WhenTheCompositionRootRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.onlyTheWiringSeesInsideAContext(
                FIXTURES, FixtureConfiguration.class),
            WiringReachingIntoContexts.class,
            WiringReachingIntoContexts.Nested.class);

    assertHolds(result);
  }

  /**
   * The build's own rule, on the one class that reaches into every context today: the proof that it
   * recognises Spring's {@code @Configuration}, which no fixture may carry.
   */
  @Test
  void GivenUseCaseConfiguration_WhenTheBuildsCompositionRootRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.only_the_wiring_sees_inside_a_context,
            UseCaseConfiguration.class);

    assertHolds(result);
  }

  @Test
  void GivenAKernelClassReferencingNotifications_WhenTheKernelRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.theSharedKernelDependsOnNoContext(FIXTURES),
            KernelReachingIntoNotifications.class);

    assertViolatedNaming(result, "FixtureNotice");
  }

  @Test
  void
      GivenAKernelClassReferencingAContextNoListNames_WhenTheKernelRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.theSharedKernelDependsOnNoContext(FIXTURES),
            KernelReachingIntoAnUnlistedContext.class);

    assertViolatedNaming(result, "FixturePayslip");
  }

  @Test
  void
      GivenAKernelClassReferencingAContextsPublishedPort_WhenTheKernelRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.theSharedKernelDependsOnNoContext(FIXTURES),
            KernelReachingIntoAPublishedPort.class);

    assertViolatedNaming(result, "FixtureNotifyUseCase");
  }

  @Test
  void GivenAKernelClassReferencingConfiguration_WhenTheKernelRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.theSharedKernelDependsOnNoContext(FIXTURES),
            KernelReachingIntoConfiguration.class);

    assertViolatedNaming(result, "FixtureWiring");
  }

  @Test
  void GivenAKernelClassKnowingOnlyTheJdk_WhenTheKernelRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.theSharedKernelDependsOnNoContext(FIXTURES),
            FixtureKernelValue.class);

    assertHolds(result);
  }

  @Test
  void GivenADomainClassUsingALibrary_WhenTheDomainRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.domain_depends_on_nothing_but_the_jdk,
            DomainWithAWireFormat.class);

    assertViolatedNaming(result, "com.fasterxml.jackson.annotation.JsonProperty");
  }

  @Test
  void GivenADomainClassUsingSpring_WhenTheDomainRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.domain_depends_on_nothing_but_the_jdk,
            DomainUsingSpring.class);

    assertViolatedNaming(result, "org.springframework.util.Assert");
  }

  @Test
  void
      GivenADomainClassReferencingAnHrSchedClassOutsideEveryDomain_WhenTheDomainRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.domain_depends_on_nothing_but_the_jdk,
            DomainKnowingTheEntryPoint.class);

    assertViolatedNaming(result, "io.github.naimelijah.hrsched.HrSchedApplication");
  }

  @Test
  void GivenDomainClassesKnowingOnlyTheJdk_WhenTheDomainRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.domain_depends_on_nothing_but_the_jdk,
            FixturePayslip.class,
            FixtureKernelValue.class);

    assertHolds(result);
  }

  @Test
  void GivenAUseCaseUsingALibrary_WhenTheApplicationRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.application_is_free_of_the_framework,
            ServiceWithAWireFormat.class);

    assertViolatedNaming(result, "tools.jackson.databind.ObjectMapper");
  }

  @Test
  void
      GivenAUseCaseReferencingAnHrSchedClassOutsideTheCore_WhenTheApplicationRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.application_is_free_of_the_framework,
            ServiceKnowingTheEntryPoint.class);

    assertViolatedNaming(result, "io.github.naimelijah.hrsched.HrSchedApplication");
  }

  @Test
  void
      GivenAUseCaseKnowingTheJdkItsPortsItsDomainAndTheKernel_WhenTheApplicationRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.application_is_free_of_the_framework,
            FixturePayrollService.class);

    assertHolds(result);
  }

  @Test
  void GivenADomainClassHoldingAFile_WhenTheIoRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(HexagonalArchitectureTest.the_core_does_no_io, DomainReadingAFile.class);

    assertViolatedNaming(result, "java.io.File");
  }

  @Test
  void GivenAUseCaseHoldingAnHttpClient_WhenTheIoRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(HexagonalArchitectureTest.the_core_does_no_io, ServiceCallingOverHttp.class);

    assertViolatedNaming(result, "java.net.http.HttpClient");
  }

  @Test
  void GivenAUseCaseHoldingAProcess_WhenTheIoRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(HexagonalArchitectureTest.the_core_does_no_io, ServiceStartingAProcess.class);

    assertViolatedNaming(result, "java.lang.ProcessBuilder");
    assertViolatedNaming(result, "<java.lang.Process>");
    assertViolatedNaming(result, "java.lang.Runtime");
  }

  @Test
  void GivenAUseCaseHoldingARemoteObjectRegistry_WhenTheIoRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(HexagonalArchitectureTest.the_core_does_no_io, ServiceCallingARemoteObject.class);

    assertViolatedNaming(result, "java.rmi.registry.Registry");
  }

  @Test
  void GivenADomainClassHoldingAnArchive_WhenTheIoRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(HexagonalArchitectureTest.the_core_does_no_io, DomainReadingAnArchive.class);

    assertViolatedNaming(result, "java.util.zip.ZipFile");
  }

  /**
   * A location is a value, and a reader, writer or stream over memory reaches nothing outside the
   * process, though each is in a package or a type hierarchy the rule otherwise refuses.
   */
  @Test
  void
      GivenADomainClassUsingAUriAndInMemoryReadersWritersAndStreams_WhenTheIoRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(HexagonalArchitectureTest.the_core_does_no_io, DomainUsingInMemoryText.class);

    assertHolds(result);
  }

  /** {@code java.io} is a package, not a verdict: its exceptions and markers do no I/O. */
  @Test
  void GivenCoreClassesUsingOnlyJavaIoNamesThatDoNoIo_WhenTheIoRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.the_core_does_no_io,
            SerializablePayslip.class,
            FixturePayrollService.class);

    assertHolds(result);
  }

  @Test
  void
      GivenAControllerItsContextsHandlerDoesNotList_WhenTheExceptionHandlerRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.everyControllerIsListedByItsContextsHandler(
                FIXTURES, FixtureController.class, FixtureExceptionHandler.class),
            PayrollController.class,
            UnhandledPayrollController.class,
            PayrollExceptionHandler.class);

    assertViolatedNaming(result, "UnhandledPayrollController");
  }

  @Test
  void
      GivenAControllerItsContextsHandlerLists_WhenTheExceptionHandlerRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.everyControllerIsListedByItsContextsHandler(
                FIXTURES, FixtureController.class, FixtureExceptionHandler.class),
            PayrollController.class,
            PayrollExceptionHandler.class);

    assertHolds(result);
  }

  @Test
  void GivenAContextClassOutsideEveryLayer_WhenTheLayerRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.everyClassInAContextIsInALayer(FIXTURES),
            PayslipFormatting.class);

    assertViolatedNaming(result, "PayslipFormatting");
  }

  /** One class from each layer, and a kernel class the rule does not ask about. */
  @Test
  void GivenContextClassesInTheirLayersAndAKernelClass_WhenTheLayerRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.everyClassInAContextIsInALayer(FIXTURES),
            FixturePayslip.class,
            FixturePayUseCase.class,
            ReachesThroughThePublishedPort.class,
            FixtureKernelValue.class);

    assertHolds(result);
  }

  @Test
  void
      GivenARepositoryLookupWithoutAnOrganisation_WhenTheTenancyRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.repository_methods_take_an_organisation,
            UnscopedPayslipRepository.class);

    assertViolatedNaming(result, "UnscopedPayslipRepository.findByReference");
  }

  /**
   * An unscoped {@code exists…} is an oracle that a record exists in some organisation, and a name
   * pattern for lookups would never have asked about it.
   */
  @Test
  void
      GivenARepositoryMethodNamedLikeNoLookupWithoutAnOrganisation_WhenTheTenancyRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.repository_methods_take_an_organisation,
            UnscopedPayslipRepository.class);

    assertViolatedNaming(result, "UnscopedPayslipRepository.existsByReference");
  }

  @Test
  void GivenADirectoryReadWithoutAnOrganisation_WhenTheTenancyRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.repository_methods_take_an_organisation,
            UnscopedPayeeDirectory.class);

    assertViolatedNaming(result, "UnscopedPayeeDirectory.payeesNamed");
  }

  @Test
  void
      GivenARepositoryWhoseLookupAndLockTakeAnOrganisation_WhenTheTenancyRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.repository_methods_take_an_organisation,
            ScopedPayslipRepository.class);

    assertHolds(result);
  }

  /**
   * An entry allows one signature, not a name: an overload added beside an allowed method, with no
   * organisation and none of the reason the entry gives, is reported.
   */
  @Test
  void
      GivenAnOverloadOfAnAllowedMethod_WhenTheTenancyRuleIsEvaluated_ThenOnlyTheOverloadIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.repositoryMethodsTakeAnOrganisation(
                List.of(
                    new UnscopedLookup(
                        UnscopedPayslipRepository.class,
                        "findByReference",
                        List.of(String.class),
                        "an invented reason, for this test alone"))),
            UnscopedPayslipRepository.class);

    assertViolatedNaming(result, "findByReference(java.lang.String, int)");
    assertThat(result.getFailureReport().getDetails())
        .as("violations of the allowed signature itself")
        .noneMatch(detail -> detail.contains("findByReference(java.lang.String) "));
  }

  /**
   * The allow-list names methods by string, so a renamed or deleted method would leave an entry
   * that exempts nothing, and a method that later gains an {@code OrganisationId} would keep an
   * entry it no longer needs. Each is looked up by its whole signature, so either fails here. A
   * reason is what makes an entry a decision rather than an omission, so none may be blank.
   */
  @Test
  void
      GivenTheUnscopedMethodsAllowed_WhenEachIsLookedUpOnItsPort_ThenEveryOneIsASignatureItDeclaresWithAReason() {
    assertThat(HexagonalArchitectureTest.UNSCOPED_LOOKUPS)
        .as("allowed unscoped methods")
        .isNotEmpty()
        .allSatisfy(
            lookup -> {
              assertThat(declares(lookup))
                  .as(
                      "%s declares %s%s",
                      lookup.port().getSimpleName(), lookup.method(), lookup.parameters())
                  .isTrue();
              assertThat(lookup.reason())
                  .as("the reason %s is allowed", lookup.method())
                  .isNotBlank();
            });
  }

  private static boolean declares(UnscopedLookup lookup) {
    try {
      lookup
          .port()
          .getDeclaredMethod(lookup.method(), lookup.parameters().toArray(Class<?>[]::new));
      return true;
    } catch (NoSuchMethodException absent) {
      return false;
    }
  }

  @Test
  void GivenAClassCallingTheOperatorConstructor_WhenTheActorRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(HexagonalArchitectureTest.only_an_account_makes_an_actor, BuildsAnOperator.class);

    assertViolatedNaming(result, "BuildsAnOperator");
  }

  @Test
  void GivenAClassReferencingTheOperatorConstructor_WhenTheActorRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.only_an_account_makes_an_actor,
            ReferencesTheOperatorConstructor.class);

    assertViolatedNaming(result, "ReferencesTheOperatorConstructor");
  }

  @Test
  void GivenAClassCallingATenantActorConstructor_WhenTheActorRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.only_an_account_makes_an_actor, BuildsATenantActor.class);

    assertViolatedNaming(result, "BuildsATenantActor");
  }

  /**
   * The account builds both actors in {@code actor()}, and {@code TenantActor}'s short constructor
   * delegates to its canonical one: the two exemptions the rule makes, and both must hold.
   */
  @Test
  void GivenTheAccountAndTheActorRecordsThemselves_WhenTheActorRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.only_an_account_makes_an_actor,
            Account.class,
            TenantActor.class,
            Operator.class);

    assertHolds(result);
  }

  @Test
  void
      GivenAClassOutsideTheAccessUseCasesCallingAccountActor_WhenTheAccountRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.only_the_access_use_cases_ask_an_account_for_its_actor,
            AsksAnAccountForItsActor.class);

    assertViolatedNaming(result, "AsksAnAccountForItsActor");
  }

  @Test
  void
      GivenAClassOutsideTheAccessUseCasesReferencingAccountActor_WhenTheAccountRuleIsEvaluated_ThenItIsViolated() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.only_the_access_use_cases_ask_an_account_for_its_actor,
            ReferencesAccountActor.class);

    assertViolatedNaming(result, "ReferencesAccountActor");
  }

  @Test
  void GivenTheSessionService_WhenTheAccountRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.only_the_access_use_cases_ask_an_account_for_its_actor,
            SessionService.class,
            // A class the rule does inspect, since a rule that inspects nothing fails as empty.
            Operator.class);

    assertHolds(result);
  }

  /**
   * The fixtures break the rules on purpose, so the build's own analysis must never see them: if it
   * did, they would fail it, or a rule loosened to let them pass would let production pass too.
   * Read from {@code HexagonalArchitectureTest}'s own annotation, so a change there is a change
   * here.
   */
  @Test
  void
      GivenTheImportHexagonalArchitectureTestDeclares_WhenTheClassesAreImported_ThenNoFixtureIsAmongThem() {
    JavaClasses classes = theBuildsAnalysis();

    assertThat(classes.contain(HrSchedApplication.class))
        .as("the build's analysis imports the production classes")
        .isTrue();
    List<String> fixtures =
        classes.stream()
            .map(JavaClass::getName)
            .filter(name -> name.startsWith(FIXTURE_ROOT + "."))
            .toList();
    assertThat(fixtures).as("fixture classes in the build's own analysis").isEmpty();
  }

  /**
   * The fixtures sit under {@code @SpringBootApplication}'s component-scan base, and the test
   * classes are on the classpath of every {@code @SpringBootTest}, so a fixture carrying a Spring
   * stereotype would join the shared test context, and a final one carrying {@code @Configuration}
   * would fail its startup. The rules that need one take the annotation as a parameter instead.
   */
  @Test
  void GivenTheFixtures_WhenTheirAnnotationsAreRead_ThenNoneIsASpringStereotype() {
    List<String> stereotyped =
        new ClassFileImporter()
            .importPackages(FIXTURE_ROOT).stream()
                .filter(
                    fixture ->
                        fixture.isAnnotatedWith(Component.class)
                            || fixture.isMetaAnnotatedWith(Component.class))
                .map(JavaClass::getName)
                .toList();

    assertThat(stereotyped).as("fixtures a component scan would pick up").isEmpty();
  }

  /**
   * Every fixture test above roots its rules at the fixtures, so none of them would notice the
   * build's own root drifting from the real base package. Both the cross-context and the kernel
   * rule would then see no context at all, and pass while checking nothing.
   */
  @Test
  void GivenTheClassesTheBuildAnalyses_WhenEachIsMappedToItsContext_ThenEveryRealContextIsFound() {
    Set<String> contexts =
        theBuildsAnalysis().stream()
            .map(ContextPackages.HRSCHED::contextOf)
            .flatMap(Optional::stream)
            .collect(Collectors.toSet());

    assertThat(contexts)
        .as("the contexts ContextPackages.HRSCHED finds in the build's analysis")
        .contains("scheduling", "workforce", "access", "notifications");
  }

  /** The classes {@code HexagonalArchitectureTest} analyses, read from its own annotation. */
  private static JavaClasses theBuildsAnalysis() {
    AnalyzeClasses analysed = HexagonalArchitectureTest.class.getAnnotation(AnalyzeClasses.class);
    ClassFileImporter importer = new ClassFileImporter();
    for (Class<? extends ImportOption> option : analysed.importOptions()) {
      importer = importer.withImportOption(instantiate(option));
    }
    return importer.importPackages(analysed.packages());
  }

  private static EvaluationResult evaluate(ArchRule rule, Class<?>... classes) {
    return rule.evaluate(new ClassFileImporter().importClasses(classes));
  }

  private static void assertViolatedNaming(EvaluationResult result, String offender) {
    assertThat(result.hasViolation()).as("the rule reports the deliberate violation").isTrue();
    assertThat(result.getFailureReport().getDetails())
        .as("the violation names %s", offender)
        .anySatisfy(detail -> assertThat(detail).contains(offender));
  }

  private static void assertHolds(EvaluationResult result) {
    assertThat(result.getFailureReport().getDetails())
        .as("violations of a rule the classes keep")
        .isEmpty();
    assertThat(result.hasViolation()).isFalse();
  }

  private static ImportOption instantiate(Class<? extends ImportOption> option) {
    try {
      return option.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot instantiate import option " + option.getName(), e);
    }
  }
}
