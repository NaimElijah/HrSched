package io.github.naimelijah.hrsched.configuration;

import static io.github.naimelijah.hrsched.configuration.persistence.TransactionalUseCases.transactional;

import io.github.naimelijah.hrsched.access.application.port.out.AccountRepository;
import io.github.naimelijah.hrsched.access.application.port.out.OrganisationRepository;
import io.github.naimelijah.hrsched.access.application.port.out.PasswordHasher;
import io.github.naimelijah.hrsched.access.application.service.AccountService;
import io.github.naimelijah.hrsched.access.application.service.OrganisationService;
import io.github.naimelijah.hrsched.notifications.application.port.out.NotificationRepository;
import io.github.naimelijah.hrsched.notifications.application.service.NotificationService;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PlanRostersUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.Notifier;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.scheduling.application.service.SchedulingService;
import io.github.naimelijah.hrsched.scheduling.application.service.SwapService;
import io.github.naimelijah.hrsched.workforce.application.port.out.EmployeeRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.application.service.EmployeeService;
import io.github.naimelijah.hrsched.workforce.application.service.QualificationService;
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
 */
@Configuration
public class UseCaseConfiguration {

  @Bean
  AccountService accountService(
      AccountRepository accounts,
      PasswordHasher passwords,
      io.github.naimelijah.hrsched.access.application.port.out.AuditTrail audit,
      Clock clock,
      PlatformTransactionManager transactions) {
    return transactional(new AccountService(accounts, passwords, audit, clock), transactions);
  }

  @Bean
  OrganisationService organisationService(
      OrganisationRepository organisations,
      io.github.naimelijah.hrsched.access.application.port.out.AuditTrail audit,
      PlatformTransactionManager transactions) {
    return transactional(new OrganisationService(organisations, audit), transactions);
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
      WorkforceDirectory workforce,
      RosterGenerator generator,
      AuditTrail audit,
      PlatformTransactionManager transactions) {
    return transactional(
        new SchedulingService(shifts, rosters, constraintSets, workforce, generator, audit),
        transactions);
  }

  /**
   * Swapping composes publication rather than repeating it, so this bean depends on the driving
   * port the scheduling service implements. Spring resolves that against the {@code
   * schedulingService} bean above; there is no cycle, because nothing in scheduling depends on
   * swaps.
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
