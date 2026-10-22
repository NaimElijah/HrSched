package io.github.naimelijah.hrsched.configuration;

import static io.github.naimelijah.hrsched.configuration.persistence.TransactionalUseCases.transactional;

import io.github.naimelijah.hrsched.access.application.port.out.AccessTokenIssuer;
import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.EmployeeDirectory;
import io.github.naimelijah.hrsched.access.application.port.out.OrganisationRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.application.port.out.SessionRepository;
import io.github.naimelijah.hrsched.access.application.port.out.SessionSecrets;
import io.github.naimelijah.hrsched.access.application.service.AccountService;
import io.github.naimelijah.hrsched.access.application.service.OrganisationService;
import io.github.naimelijah.hrsched.access.application.service.SessionService;
import io.github.naimelijah.hrsched.access.domain.model.LockoutPolicy;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.SessionPolicy;
import io.github.naimelijah.hrsched.configuration.scheduling.GenerationPolicyProperties;
import io.github.naimelijah.hrsched.configuration.security.SecurityPolicyProperties;
import io.github.naimelijah.hrsched.notifications.application.port.out.NotificationRepository;
import io.github.naimelijah.hrsched.notifications.application.service.NotificationService;
import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerateRosterCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PlanRostersUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.scheduling.application.port.out.BackgroundWork;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.Notifier;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ObjectiveWeightsRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerationRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.UnitsOfWork;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.scheduling.application.service.SchedulingService;
import io.github.naimelijah.hrsched.scheduling.application.service.SwapService;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationPolicy;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.workforce.application.port.out.EmployeeRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.application.service.EmployeeService;
import io.github.naimelijah.hrsched.workforce.application.service.QualificationService;
import java.lang.reflect.Method;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the application layer into Spring.
 *
 * <p>This class exists so that application services need no Spring annotation and stay
 * constructible with {@code new} in a unit test. The cost is that every new use case must be
 * registered here by hand; the benefit is that the framework stays outside the hexagon. See
 * ADR-003.
 *
 * <p>It is also the one place permitted to see more than one context at a time, which is why the
 * architecture rules exempt classes annotated {@code @Configuration} and nothing else.
 *
 * <p>That privilege has a cost visible below: each context owns an {@code AuditTrail} port of its
 * own, so all four share a simple name that only collides here. Three of them are written out in
 * full rather than renamed, because inside its own context {@code AuditTrail} is the right name and
 * seeing all four at once is this class's job, not theirs.
 *
 * <p><strong>Every use case below is transactional, and nothing about the services says
 * so.</strong> {@code transactional(...)} wraps each one, because the architecture test forbids
 * Spring inside the application layer and so {@code @Transactional} cannot go where a reader would
 * look for it. A transaction begins when a driving port method is called and ends when it returns;
 * a nested use case joins its caller's. See ADR-023.
 *
 * <p>One method is the exception, and it is named here: starting a generation marks out its own
 * units of work, so that the generation is stored before its solve is handed to a thread, and the
 * solve holds no connection (ADR-045, ADR-046).
 */
@Configuration
public class UseCaseConfiguration {

  @Bean
  AccountService accountService(
      AccountRepository accounts,
      SessionRepository sessions,
      EmployeeDirectory employees,
      PasswordHasher passwords,
      PasswordPolicy passwordPolicy,
      io.github.naimelijah.hrsched.access.application.port.out.AuditTrail audit,
      Clock clock,
      PlatformTransactionManager transactions) {
    return transactional(
        new AccountService(accounts, sessions, employees, passwords, passwordPolicy, audit, clock),
        transactions);
  }

  /**
   * Turns the configured numbers into the policy the domain understands.
   *
   * <p>A bean rather than a field read inside the use case, so that what counts as too many
   * failures is an input a test supplies rather than an ambient fact it has to live with.
   */
  @Bean
  LockoutPolicy lockoutPolicy(SecurityPolicyProperties policies) {
    return new LockoutPolicy(
        policies.signIn().lockoutThreshold(), policies.signIn().lockoutDuration());
  }

  /** As {@link #lockoutPolicy}, for how long a session lives. */
  @Bean
  SessionPolicy sessionPolicy(SecurityPolicyProperties policies) {
    return new SessionPolicy(
        policies.session().absoluteLifetime(), policies.session().idleLifetime());
  }

  /**
   * What a password somebody chooses has to satisfy: a floor from configuration, and a ceiling the
   * hasher imposes. Asking the hasher for its own limit keeps the two from drifting apart if the
   * algorithm ever changes.
   */
  @Bean
  PasswordPolicy passwordPolicy(SecurityPolicyProperties policies, PasswordHasher hasher) {
    return new PasswordPolicy(policies.password().minimumLength(), hasher.maximumBytes());
  }

  @Bean
  SessionService sessionService(
      AccountRepository accounts,
      SessionRepository sessions,
      PasswordHasher passwords,
      AccessTokenIssuer tokens,
      SessionSecrets secrets,
      io.github.naimelijah.hrsched.access.application.port.out.AuditTrail audit,
      Clock clock,
      LockoutPolicy lockout,
      SessionPolicy sessionPolicy,
      PasswordPolicy passwordPolicy,
      PlatformTransactionManager transactions) {
    return transactional(
        new SessionService(
            accounts,
            sessions,
            passwords,
            tokens,
            secrets,
            audit,
            clock,
            lockout,
            sessionPolicy,
            passwordPolicy),
        transactions);
  }

  @Bean
  OrganisationService organisationService(
      OrganisationRepository organisations,
      AccountRepository accounts,
      PasswordHasher passwords,
      PasswordPolicy passwordPolicy,
      io.github.naimelijah.hrsched.access.application.port.out.AuditTrail audit,
      Clock clock,
      PlatformTransactionManager transactions) {
    return transactional(
        new OrganisationService(organisations, accounts, passwords, passwordPolicy, audit, clock),
        transactions);
  }

  /**
   * The clock the application layer reads time from.
   *
   * <p>A bean rather than a call to {@code Instant.now()} inside a use case, so that when something
   * happened is an input a test supplies rather than an ambient fact it has to tolerate. {@code
   * java.time.Clock} is the JDK, so injecting it keeps the hexagon framework-free.
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  NotificationService notificationService(
      NotificationRepository notifications,
      io.github.naimelijah.hrsched.notifications.application.port.out.AuditTrail audit,
      Clock clock,
      PlatformTransactionManager transactions) {
    return transactional(new NotificationService(notifications, audit, clock), transactions);
  }

  @Bean
  SchedulingService schedulingService(
      ShiftRepository shifts,
      RosterRepository rosters,
      ConstraintSetRepository constraintSets,
      ObjectiveWeightsRepository objectiveWeights,
      SwapRequestRepository swaps,
      WorkforceDirectory workforce,
      RosterGenerator generator,
      UnitsOfWork units,
      RosterGenerationRepository generations,
      BackgroundWork background,
      GenerationPolicy generationPolicy,
      Notifier notifier,
      AuditTrail audit,
      Clock clock,
      PlatformTransactionManager transactions) {
    return transactional(
        new SchedulingService(
            shifts,
            rosters,
            constraintSets,
            objectiveWeights,
            swaps,
            workforce,
            generator,
            units,
            generations,
            background,
            generationPolicy,
            notifier,
            audit,
            clock),
        transactions,
        startingAGeneration());
  }

  /** How long a generation may stay pending before it is reported lost (ADR-046). */
  @Bean
  GenerationPolicy generationPolicy(GenerationPolicyProperties properties) {
    return new GenerationPolicy(properties.lostAfter());
  }

  /**
   * The use case left outside any transaction (ADR-045, ADR-046), resolved here rather than named
   * by a string the proxy compares: if it is renamed, startup fails, instead of a generation
   * quietly being handed to a thread before the transaction storing it commits.
   */
  private static Method startingAGeneration() {
    try {
      return PlanRostersUseCase.class.getMethod(
          "startGeneration", Actor.class, GenerateRosterCommand.class);
    } catch (NoSuchMethodException renamed) {
      throw new IllegalStateException(
          "PlanRostersUseCase.startGeneration is the use case ADR-046 leaves outside any"
              + " transaction, and it no longer exists by that name",
          renamed);
    }
  }

  /**
   * Swapping composes publication rather than repeating it, so this bean depends on the driving
   * port the scheduling service implements. Spring resolves that against the {@code
   * schedulingService} bean above. There is no cycle: publication lapses swap requests (R33)
   * through the swap store and the notifier, both driven ports, and never through this bean.
   */
  @Bean
  SwapService swapService(
      SwapRequestRepository swaps,
      RosterRepository rosters,
      ShiftRepository shifts,
      WorkforceDirectory workforce,
      PlanRostersUseCase planRosters,
      Notifier notifier,
      AuditTrail audit,
      Clock clock,
      PlatformTransactionManager transactions) {
    return transactional(
        new SwapService(swaps, rosters, shifts, workforce, planRosters, notifier, audit, clock),
        transactions);
  }

  @Bean
  EmployeeService employeeService(
      EmployeeRepository employees,
      QualificationRepository qualifications,
      io.github.naimelijah.hrsched.workforce.application.port.out.AuditTrail audit,
      PlatformTransactionManager transactions) {
    return transactional(new EmployeeService(employees, qualifications, audit), transactions);
  }

  @Bean
  QualificationService qualificationService(
      QualificationRepository qualifications,
      io.github.naimelijah.hrsched.workforce.application.port.out.AuditTrail audit,
      PlatformTransactionManager transactions) {
    return transactional(new QualificationService(qualifications, audit), transactions);
  }
}
