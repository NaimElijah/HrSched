package io.github.naimelijah.hrsched.shared.testsupport;

import io.github.naimelijah.hrsched.scheduling.application.port.out.BackgroundWork;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Points a {@code @SpringBootTest} at the PostgreSQL the suite already has running.
 *
 * <p>{@code @Import} this from any test that boots the application. Boot's datasource
 * auto-configuration takes its URL, user and password from the {@link JdbcConnectionDetails} bean
 * here, so no test has to name a property, and Flyway then applies the real migrations to it.
 *
 * <p>The container is the one {@link PostgresDatabase} already started, so the whole suite pays for
 * a single container once, and Spring is given only its address, never the container as a bean. A
 * container bean is stopped when its context closes, by Spring's inferred {@code close()} and, were
 * Boot's Testcontainers module on the classpath, by its lifecycle whatever the {@code
 * destroyMethod} (Boot 4.1.2). Either would let any test that closes a context it started take the
 * database away from every test after it (#252). The pom keeps that module off the classpath, and
 * {@code PostgresBackedApplicationTest} pins that a context can close and leave the database
 * answering.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresBackedApplication {

  @Bean
  JdbcConnectionDetails sharedPostgres() {
    PostgreSQLContainer container = PostgresDatabase.container();
    return new JdbcConnectionDetails() {
      @Override
      public String getJdbcUrl() {
        return container.getJdbcUrl();
      }

      @Override
      public String getUsername() {
        return container.getUsername();
      }

      @Override
      public String getPassword() {
        return container.getPassword();
      }
    };
  }

  /**
   * Runs each generation on the thread that asked for it, so a draft exists by the time the request
   * starting it has been answered, and no test has to wait on another thread to see it (ADR-046).
   * The pool generations run on in a deployment is specified on its own, in {@code
   * GenerationRunnerTest}.
   */
  @Bean
  @Primary
  BackgroundWork generateOnTheCallingThread() {
    return Runnable::run;
  }
}
