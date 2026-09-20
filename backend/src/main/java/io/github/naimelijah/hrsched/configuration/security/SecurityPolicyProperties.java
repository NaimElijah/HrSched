package io.github.naimelijah.hrsched.configuration.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The numbers that decide how long a session lasts and when an account locks.
 *
 * <p>Every one of them is configuration and none has a default in code, for the same reason no
 * working-time threshold in this codebase is a constant: how much guessing is too much, and how
 * long somebody stays signed in, are decisions for whoever runs this and carries the consequences.
 * The values are in {@code application.properties} with their reasoning beside them.
 *
 * <p>Lives in {@code configuration} rather than in the {@code access} adapters because it is what
 * the composition root turns into the domain's {@code LockoutPolicy} and {@code SessionPolicy}
 * before handing them to a use case — the application layer receives the policies, never these.
 *
 * @param signIn what it takes to lock an account, and for how long
 * @param session how long a session may live, in total and while idle
 */
@ConfigurationProperties("hrsched.security")
public record SecurityPolicyProperties(SignIn signIn, Session session) {

  /**
   * @param lockoutThreshold consecutive failures that lock an account
   * @param lockoutDuration how long that lock lasts
   */
  public record SignIn(int lockoutThreshold, Duration lockoutDuration) {}

  /**
   * @param absoluteLifetime how long a session may live in total, never extended by use
   * @param idleLifetime how long it survives without being renewed
   */
  public record Session(Duration absoluteLifetime, Duration idleLifetime) {}
}
