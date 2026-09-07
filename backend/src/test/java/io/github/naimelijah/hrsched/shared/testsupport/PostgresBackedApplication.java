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
 *
 * <p>{@code destroyMethod = ""} is load-bearing. Boot treats a container bean as something it owns
 * and closes it when the context is destroyed, which for a container shared with every plain
 * adapter test would stop the database out from under them on the first context eviction. This bean
 * is a reference to something it did not start, so it must not stop it either.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresBackedApplication {

  @Bean(destroyMethod = "")
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return PostgresDatabase.container();
  }
}
