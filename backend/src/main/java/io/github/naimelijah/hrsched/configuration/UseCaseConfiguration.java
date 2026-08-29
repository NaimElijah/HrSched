package io.github.naimelijah.hrsched.configuration;

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
 */
@Configuration
public class UseCaseConfiguration {

  @Bean
  SchedulingService schedulingService(
      ShiftRepository shifts,
      RosterRepository rosters,
      ConstraintSetRepository constraintSets,
      WorkforceDirectory workforce,
      RosterGenerator generator) {
    return new SchedulingService(shifts, rosters, constraintSets, workforce, generator);
  }

  @Bean
  EmployeeService employeeService(
      EmployeeRepository employees, QualificationRepository qualifications) {
    return new EmployeeService(employees, qualifications);
  }

  @Bean
  QualificationService qualificationService(QualificationRepository qualifications) {
    return new QualificationService(qualifications);
  }
}
