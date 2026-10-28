package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.HrSchedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Specification that the JSON body limit cannot be switched off by configuration (issue #250).
 *
 * <p>Jackson 3 reads a maximum document length of zero or less as "no limit", and Boot hands it
 * whatever {@code hrsched.web.max-json-document-bytes} says. So a deployment that set it to 0,
 * meaning to forbid bodies or by mistake, would start cleanly with no limit at all, and sign-in,
 * which takes no token, would buffer whatever it was sent. A limit that is not a positive number of
 * bytes must stop the application from starting instead.
 *
 * <p>Each case starts the whole application, because the limit is only a property until the
 * application binds it, and no lighter context can say whether a full start refuses it. That costs
 * a context per case, so there are three: zero, a negative number, and the control. The control
 * starts the same way with a positive limit, so a refusal below is the value's doing and not the
 * harness's.
 *
 * <p>The suite's database is handed over as plain datasource properties, not through {@code
 * PostgresBackedApplication}'s container bean. These contexts are closed as soon as they start, and
 * closing a context that holds the container as a bean stops it: Boot's Testcontainers lifecycle
 * closes a container bean whose destroy method is {@code ""} itself, and every test after this one
 * would lose the database.
 *
 * <p>The limits are invented for the test.
 */
class JsonDocumentLimitStartupTest {

  private static final String LIMIT = "hrsched.web.max-json-document-bytes";

  /** Invented: positive, and nothing else about it matters. */
  private static final String INVENTED_POSITIVE_LIMIT = "4096";

  @Test
  void GivenALimitOfZero_WhenTheApplicationStarts_ThenStartupFails() {
    assertThatThrownBy(() -> startWithLimit("0"))
        .describedAs("starting with %s=0", LIMIT)
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void GivenANegativeLimit_WhenTheApplicationStarts_ThenStartupFails() {
    assertThatThrownBy(() -> startWithLimit("-1"))
        .describedAs("starting with %s=-1", LIMIT)
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void GivenAPositiveLimit_WhenTheApplicationStarts_ThenItStarts() {
    assertThatCode(() -> startWithLimit(INVENTED_POSITIVE_LIMIT))
        .describedAs("starting with %s=%s", LIMIT, INVENTED_POSITIVE_LIMIT)
        .doesNotThrowAnyException();
  }

  /**
   * Starts the application against the suite's database, on a free port, and closes it again if it
   * started. Docker Compose support is switched off, as a test context switches it off, so the
   * local compose file is never started.
   *
   * <p>The limit is a command-line argument, not one of the builder's properties. Those are default
   * properties, the lowest-ranked source there is, so {@code application.properties} overrode the
   * limit given there and every case started with the committed one.
   */
  private static void startWithLimit(String limit) {
    PostgreSQLContainer database = PostgresDatabase.container();
    try (ConfigurableApplicationContext ignored =
        new SpringApplicationBuilder(HrSchedApplication.class)
            .properties(
                "server.port=0",
                "spring.docker.compose.enabled=false",
                "spring.datasource.url=" + database.getJdbcUrl(),
                "spring.datasource.username=" + database.getUsername(),
                "spring.datasource.password=" + database.getPassword())
            .run("--" + LIMIT + "=" + limit)) {
      // Started; closing it is all that is left to do.
    }
  }
}
