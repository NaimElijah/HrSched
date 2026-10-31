package io.github.naimelijah.hrsched;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the HrSched staff shift scheduling service.
 *
 * <p>This class carries no logic on purpose: it only starts Spring and scans from the root package.
 * The application layer is wired in {@code configuration.UseCaseConfiguration}, which keeps the
 * domain and the application services free of Spring and constructible without booting it. See
 * {@code docs/architecture/architecture.md} for the dependency direction this preserves.
 *
 * <p>{@code @ConfigurationPropertiesScan} is here rather than on a configuration class because the
 * property records it binds live in more than one package: beside the adapter that reads one, or in
 * {@code configuration} for one the composition root turns into a domain policy or one that only
 * guards a value handed to the framework. Scanning from the root finds them all. It binds types; it
 * does not reference them, so the dependency rule is untouched.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class HrSchedApplication {

  /**
   * Starts the service.
   *
   * @param args command-line arguments, handed to Spring Boot as property sources
   */
  public static void main(String[] args) {
    SpringApplication.run(HrSchedApplication.class, args);
  }
}
