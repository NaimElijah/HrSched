package io.github.naimelijah.hrsched.configuration;

import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.scheduling.application.service.SchedulingService;
import io.github.naimelijah.hrsched.workforce.application.port.out.EmployeeRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.application.service.EmployeeService;
import io.github.naimelijah.hrsched.workforce.application.service.QualificationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * own, so the two share a simple name that only collides here. The workforce one is written out in
 * full rather than renamed, because inside its own context {@code AuditTrail} is the right name and
 * seeing both at once is this class's job, not theirs.
 */
@Configuration
public class UseCaseConfiguration {

  @Bean
  SchedulingService schedulingService(
      ShiftRepository shifts,
      RosterRepository rosters,
      ConstraintSetRepository constraintSets,
      WorkforceDirectory workforce,
      RosterGenerator generator,
      AuditTrail audit) {
    return new SchedulingService(shifts, rosters, constraintSets, workforce, generator, audit);
  }

  @Bean
  EmployeeService employeeService(
      EmployeeRepository employees,
      QualificationRepository qualifications,
      io.github.naimelijah.hrsched.workforce.application.port.out.AuditTrail audit) {
    return new EmployeeService(employees, qualifications, audit);
  }

  @Bean
  QualificationService qualificationService(
      QualificationRepository qualifications,
      io.github.naimelijah.hrsched.workforce.application.port.out.AuditTrail audit) {
    return new QualificationService(qualifications, audit);
  }
}
