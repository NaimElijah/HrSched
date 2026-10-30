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
import io.github.naimelijah.hrsched.architecture.fixtures.actors.AsksAnAccountForItsActor;
import io.github.naimelijah.hrsched.architecture.fixtures.actors.BuildsATenantActor;
import io.github.naimelijah.hrsched.architecture.fixtures.actors.BuildsAnOperator;
import io.github.naimelijah.hrsched.architecture.fixtures.actors.ReferencesAccountActor;
import io.github.naimelijah.hrsched.architecture.fixtures.actors.ReferencesTheOperatorConstructor;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.out.notifications.ReachesIntoAnotherContextsDomain;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.out.notifications.ReachesIntoAnotherContextsDrivenPort;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.adapter.out.notifications.ReachesThroughThePublishedPort;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.DomainKnowingTheEntryPoint;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.DomainUsingSpring;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.DomainWithAWireFormat;
import io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model.FixturePayslip;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.FixtureKernelValue;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.KernelReachingIntoAPublishedPort;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.KernelReachingIntoAnUnlistedContext;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.KernelReachingIntoConfiguration;
import io.github.naimelijah.hrsched.architecture.fixtures.shared.domain.model.KernelReachingIntoNotifications;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

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

  @Test
  void
      GivenAContextReferencingAPublishedPortItsOwnDomainAndTheKernel_WhenTheCrossContextRuleIsEvaluated_ThenItHolds() {
    EvaluationResult result =
        evaluate(
            HexagonalArchitectureTest.contextsTouchOnlyPublishedPortsOfOtherContexts(FIXTURES),
            ReachesThroughThePublishedPort.class);

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
