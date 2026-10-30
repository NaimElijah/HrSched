package io.github.naimelijah.hrsched;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Smoke test proving the Spring context is wired consistently and the application can start.
 *
 * <p>Annotated identically to the HTTP tests, MockMvc included though it sends no request, so the
 * context it proves is the one the suite runs on rather than one started for this test alone.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class HrSchedApplicationTests {

  @Test
  void GivenDefaultConfiguration_WhenApplicationStarts_ThenContextLoads() {}
}
