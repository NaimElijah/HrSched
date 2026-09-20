package io.github.naimelijah.hrsched;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the HrSched staff shift scheduling service.
 *
 * <p>This class carries no logic on purpose: it is the composition root, and the domain must stay
 * reachable without booting Spring. See {@code docs/architecture/architecture.md} for the
 * dependency direction this preserves.
 *
 * <p>{@code @ConfigurationPropertiesScan} is here rather than on a configuration class because the
 * property records it binds live beside the adapters that read them, which is where knowledge of a
 * setting belongs. It binds types; it does not reference them, so the dependency rule is untouched.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class HrSchedApplication {

  public static void main(String[] args) {
    SpringApplication.run(HrSchedApplication.class, args);
  }
}
