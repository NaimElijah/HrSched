package io.github.naimelijah.hrsched.shared.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.HrSchedApplication;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Specification that a context pointed at the suite's database cannot take that database down when
 * it closes (#252).
 *
 * <p>Every test in the suite shares one PostgreSQL container, which {@link PostgresDatabase} starts
 * once per JVM. A context started over it, and closed before the JVM exits, must leave it running:
 * otherwise every test after the one that closed it fails with "Connection refused", for reasons
 * that have nothing to do with the code under test.
 *
 * <p>The context is started by hand rather than by {@code @SpringBootTest}, so closing it is this
 * test's own act and the suite's cached context is never touched. It goes through the same {@code
 * SpringApplication} start every test context does. A container held as a bean would be stopped
 * when its context closes, by Spring's inferred {@code close()} and, should Boot's Testcontainers
 * module return to the classpath, by its lifecycle too. This test fails if either takes the
 * database away.
 */
class PostgresBackedApplicationTest {

  @Test
  void GivenAContextOverTheSharedDatabase_WhenItIsClosed_ThenTheDatabaseStillAnswers() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(HrSchedApplication.class, PostgresBackedApplication.class)
            .properties("server.port=0", "spring.docker.compose.enabled=false")
            .run()) {
      assertThat(answerFrom(JdbcClient.create(context.getBean(DataSource.class))))
          .describedAs("the context reaches the shared database while it runs")
          .isEqualTo(1);
    }

    assertThat(answerFrom(PostgresDatabase.jdbcClient()))
        .describedAs("the shared database still answers once the context is closed")
        .isEqualTo(1);
  }

  private static int answerFrom(JdbcClient jdbc) {
    return jdbc.sql("SELECT 1").query(Integer.class).single();
  }
}
