package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.access.domain.model.OperatorNameHeldByATenantException;

/**
 * Driving port: making sure the platform has an operator to sign in as.
 *
 * <p>Somebody has to be able to onboard the first organisation, and every other account is created
 * by an account that already exists. This is where that chain starts, which is why it is driven by
 * configuration at startup rather than by a request.
 *
 * <p>Idempotent on purpose. It runs on every start, and configuration outlives a restart, so
 * creating unconditionally would mean the environment's password silently replacing whatever the
 * operator had since chosen - and continuing to work forever.
 */
public interface BootstrapOperatorUseCase {

  /**
   * Creates the platform operator if no account holds that sign-in name.
   *
   * @param command the name and temporary password to create it with, never {@code null}
   * @return whether this call created the account; false means the operator already held the name,
   *     and nothing was changed
   * @throws OperatorNameHeldByATenantException if a tenant account holds the name, so no operator
   *     exists and none can be created under it; nothing was changed, and the message never
   *     contains the name
   */
  boolean ensureOperator(BootstrapOperatorCommand command);
}
