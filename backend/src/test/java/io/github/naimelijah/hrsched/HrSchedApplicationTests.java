package io.github.naimelijah.hrsched;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Smoke test proving the Spring context is wired consistently and the application can start. */
@Import(PostgresBackedApplication.class)
@SpringBootTest
class HrSchedApplicationTests {

  @Test
  void GivenDefaultConfiguration_WhenApplicationStarts_ThenContextLoads() {}
}
