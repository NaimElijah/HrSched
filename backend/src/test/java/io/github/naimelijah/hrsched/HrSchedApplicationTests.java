package io.github.naimelijah.hrsched;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Smoke test proving the Spring context is wired consistently and the application can start. */
@SpringBootTest
class HrSchedApplicationTests {

  @Test
  void GivenDefaultConfiguration_WhenApplicationStarts_ThenContextLoads() {}
}
