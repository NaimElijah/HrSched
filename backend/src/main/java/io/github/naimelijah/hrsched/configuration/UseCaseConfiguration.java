package io.github.naimelijah.hrsched.configuration;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.application.service.ShiftService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application layer into Spring.
 *
 * <p>This class exists so that application services need no Spring annotation and stay
 * constructible with {@code new} in a unit test. The cost is that every new use case must be
 * registered here by hand; the benefit is that the framework stays outside the hexagon. See
 * ADR-003.
 */
@Configuration
public class UseCaseConfiguration {

  /**
   * @param shiftRepository the driven port implementation found by component scanning
   * @return the service backing both shift use cases
   */
  @Bean
  ShiftService shiftService(ShiftRepository shiftRepository) {
    return new ShiftService(shiftRepository);
  }
}
