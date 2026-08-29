package io.github.naimelijah.hrsched;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the HrSched staff shift scheduling service.
 *
 * <p>This class carries no logic on purpose: it is the composition root, and the domain must stay
 * reachable without booting Spring. See {@code docs/architecture/architecture.md} for the
 * dependency direction this preserves.
 */
@SpringBootApplication
public class HrSchedApplication {

  public static void main(String[] args) {
    SpringApplication.run(HrSchedApplication.class, args);
  }
}
