package io.github.naimelijah.hrsched.access.adapter.in.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The credentials the first platform operator is created with, if any.
 *
 * <p>Both are optional here rather than required, because a deployment whose operator already
 * exists has no reason to keep supplying them - and every reason to stop, since they are a
 * credential sitting in an environment. Setting exactly one is a misconfiguration, and {@link
 * OperatorBootstrap} refuses it rather than guessing which half was meant.
 *
 * <p>Comes from {@code HRSCHED_BOOTSTRAP_OPERATOR_SIGNINNAME} and {@code
 * HRSCHED_BOOTSTRAP_OPERATOR_PASSWORD}, and never from a properties file in this repository. Local
 * development supplies throwaway values through {@code spring-boot:run}, as {@code compose.yaml}
 * already does for the database.
 *
 * <p>{@link #toString()} reveals neither. The password is the most privileged credential the
 * platform has, the sign-in name is a person's, and a configuration object gets printed - by Boot,
 * a debugger, an exception message - without anybody having meant to.
 *
 * @param signInName the name the operator signs in with, or null to create none
 * @param password the temporary password, which must be changed at first sign-in, or null
 */
@ConfigurationProperties("hrsched.bootstrap.operator")
public record BootstrapOperatorProperties(String signInName, String password) {

  @Override
  public String toString() {
    return "BootstrapOperatorProperties[redacted]";
  }
}
