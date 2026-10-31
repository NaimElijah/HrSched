package io.github.naimelijah.hrsched.access.adapter.in.bootstrap;

import io.github.naimelijah.hrsched.access.application.port.in.BootstrapOperatorCommand;
import io.github.naimelijah.hrsched.access.application.port.in.BootstrapOperatorUseCase;
import io.github.naimelijah.hrsched.access.domain.model.OperatorNameHeldByATenantException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the platform operator at startup, from configuration.
 *
 * <p>A driving adapter like any other: configuration is its transport, the way HTTP is a
 * controller's. It decides nothing about accounts — whether to create one, and what that account
 * looks like, is the use case's — and only turns two properties into a command, or refuses.
 *
 * <p>Half-configured is a failure rather than a no-op. A deployment that set a name and forgot a
 * password would otherwise start, create nothing, and be indistinguishable from one that meant to
 * create nothing at all; the operator would discover it when they could not sign in.
 *
 * <p>Nothing it logs names anybody. The outcome — created, or already present — is all that is
 * useful to whoever reads a startup log, and the sign-in name and password are exactly what must
 * not be there.
 */
@Component
public class OperatorBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(OperatorBootstrap.class);

  private static final String SIGN_IN_NAME_PROPERTY = "hrsched.bootstrap.operator.sign-in-name";
  private static final String PASSWORD_PROPERTY = "hrsched.bootstrap.operator.password";

  private final BootstrapOperatorUseCase bootstrap;
  private final BootstrapOperatorProperties properties;

  public OperatorBootstrap(
      BootstrapOperatorUseCase bootstrap, BootstrapOperatorProperties properties) {
    this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  /**
   * @param arguments the application's own arguments, which this ignores
   * @throws IllegalStateException if exactly one half of the configuration is set, which fails
   *     startup; the message names the missing property and never a value
   * @throws OperatorNameHeldByATenantException if the configured name belongs to a tenant account,
   *     which fails startup too: no operator can be created under it. Since a tenant exists only
   *     once an operator has onboarded it, the fix is to set {@code
   *     hrsched.bootstrap.operator.sign-in-name} to the existing operator's name, or to unset
   *     {@code hrsched.bootstrap.operator.*} - not to choose a free name, which would create a
   *     second operator
   * @throws io.github.naimelijah.hrsched.access.domain.model.InvalidSignInNameException if the
   *     configured name is not one an account could hold, which fails startup too
   */
  @Override
  public void run(ApplicationArguments arguments) {
    boolean hasName = isSet(properties.signInName());
    boolean hasPassword = isSet(properties.password());
    if (!hasName && !hasPassword) {
      log.info("No platform operator is configured, so none was created");
      return;
    }
    if (!hasName) {
      throw new IllegalStateException(
          "%s is set but %s is not; set both to create the platform operator, or neither"
              .formatted(PASSWORD_PROPERTY, SIGN_IN_NAME_PROPERTY));
    }
    if (!hasPassword) {
      throw new IllegalStateException(
          "%s is set but %s is not; set both to create the platform operator, or neither"
              .formatted(SIGN_IN_NAME_PROPERTY, PASSWORD_PROPERTY));
    }
    boolean created =
        bootstrap.ensureOperator(
            new BootstrapOperatorCommand(properties.signInName(), properties.password()));
    if (created) {
      log.info("Created the platform operator; its password must be changed at first sign-in");
    } else {
      log.info("The configured platform operator already exists and was left unchanged");
    }
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }
}
