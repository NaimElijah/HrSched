package io.github.naimelijah.hrsched.shared.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Points a {@code @SpringBootTest} at the PostgreSQL the suite already has running.
 *
 * <p>{@code @Import} this from any test that boots the application. {@code @ServiceConnection}
 * supplies the URL, user and password to Boot's auto-configuration, so no test has to name a
 * property, and Flyway then applies the real migrations to it.
 *
 * <p>It hands back the container {@link PostgresDatabase} already started rather than declaring one
 * of its own, so the whole suite pays for a single container once.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresBackedApplication {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return PostgresDatabase.container();
  }
}
