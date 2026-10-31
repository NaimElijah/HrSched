package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.access.domain.model.CannotResetOwnPasswordException;
import io.github.naimelijah.hrsched.access.domain.model.LastActiveAdminException;
import io.github.naimelijah.hrsched.access.domain.model.NotAnEmployeeHereException;
import io.github.naimelijah.hrsched.access.domain.model.SignInNameTakenException;
import io.github.naimelijah.hrsched.access.domain.model.UnacceptablePasswordException;
import io.github.naimelijah.hrsched.access.domain.model.UnknownAccountException;
import io.github.naimelijah.hrsched.access.domain.model.UnlinkedEmployeeAccountException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.List;

/**
 * Driving port: an Admin administering who may act inside their own organisation (requirement F15).
 *
 * <p>Accounts are provisioned from the top down (ADR-029). Nobody signs themselves up: an Admin
 * creates an account with a password they choose, and its owner must replace that password before
 * they can do anything else. There is no invitation email and no self-service registration, so
 * there is also no unauthenticated endpoint that creates anything.
 *
 * <p>The platform operator is refused every method here. Administering a tenant's staff is inside
 * that tenant, and the operator is confined to the register of organisations (ADR-030).
 *
 * <p>Refusals are thrown rather than returned, unlike signing in. A refusal here means the request
 * was wrong, and rolling the transaction back is what should happen; the returned-refusal shape
 * exists for sign-in, where the refusal is an ordinary outcome that must still leave a recorded
 * failure behind (ADR-011).
 */
public interface ManageAccountsUseCase {

  /**
   * Creates an account that can sign in, and requires its owner to choose their own password first.
   *
   * @param actor the Admin creating it
   * @param command what to create
   * @return the identity of the new account
   * @throws CrossTenantAccessException if the actor may not act in that organisation
   * @throws UnacceptablePasswordException if the temporary password does not satisfy the policy
   * @throws SignInNameTakenException if another account already signs in under that name
   * @throws NotAnEmployeeHereException if the employee named is not one of this organisation's
   * @throws UnlinkedEmployeeAccountException if it holds the Employee role and names no employee
   *     (R34)
   */
  UserId createAccount(Actor actor, CreateAccountCommand command);

  /**
   * @param actor the Admin asking
   * @param organisation whose accounts to list
   * @return every account of that organisation, oldest first
   * @throws CrossTenantAccessException if the actor may not act in that organisation
   */
  List<PublishedAccount> findAccounts(Actor actor, OrganisationId organisation);

  /**
   * One account, as {@link #findAccounts} lists it: what a creation's {@code Location} names.
   *
   * @param actor the Admin asking
   * @param organisation whose account it must be
   * @param account which account
   * @return that account
   * @throws CrossTenantAccessException if the actor may not act in that organisation
   * @throws UnknownAccountException if that organisation holds no such account, which is also what
   *     another organisation's account is, so the answer confirms nothing about it
   */
  PublishedAccount findAccount(Actor actor, OrganisationId organisation, UserId account);

  /**
   * Changes what an account is inside the organisation: its role, and the person it belongs to.
   *
   * <p>Both at once because they are one value. A role and an employee link that were set by two
   * requests could disagree in between, and an account is either somebody's or nobody's.
   *
   * <p>Takes effect on the account's next request rather than ending its sessions: who is acting is
   * resolved from the stored account every time (ADR-011), so a demotion is immediate without one.
   *
   * @param actor the Admin making the change
   * @param command which account, and what it becomes
   * @throws CrossTenantAccessException if the actor may not act in that organisation
   * @throws UnknownAccountException if that organisation holds no such account
   * @throws LastActiveAdminException if it would leave the organisation with no active Admin
   * @throws NotAnEmployeeHereException if the employee named is not one of this organisation's
   * @throws UnlinkedEmployeeAccountException if it is to hold the Employee role and names no
   *     employee (R34)
   */
  void changeMembership(Actor actor, ChangeMembershipCommand command);

  /**
   * Disables an account, or restores one.
   *
   * <p>Disabling ends every session it holds, because a credential that still works until a token
   * expires is a credential that still works.
   *
   * @param actor the Admin making the change
   * @param command which account, and whether it may act
   * @throws CrossTenantAccessException if the actor may not act in that organisation
   * @throws UnknownAccountException if that organisation holds no such account
   * @throws LastActiveAdminException if disabling it would leave no active Admin
   * @throws UnlinkedEmployeeAccountException if restoring an Employee account linked to nobody,
   *     which must be linked first: linking it does not restore it (R34)
   */
  void setAccountActive(Actor actor, SetAccountActiveCommand command);

  /**
   * Gives <em>somebody else's</em> account a new temporary password, which its owner must then
   * replace.
   *
   * <p>Never one's own. Changing your own password takes the current one (F14), which is what stops
   * a stolen token being turned into a password its owner does not know; an Admin able to reset
   * themselves here would hold that protection and a way around it at the same time.
   *
   * <p>Also clears any lockout and ends every session the account holds. Clearing the lockout is
   * the point as much as the password is: an Admin resetting a colleague who is locked out is how
   * somebody gets back in, and leaving the lock running would make the reset useless for as long as
   * the lock lasts.
   *
   * @param actor the Admin making the change
   * @param command which account, and the password to give it
   * @throws CrossTenantAccessException if the actor may not act in that organisation
   * @throws UnknownAccountException if that organisation holds no such account
   * @throws CannotResetOwnPasswordException if the actor aimed it at their own account
   * @throws UnacceptablePasswordException if the temporary password does not satisfy the policy
   */
  void resetPassword(Actor actor, ResetPasswordCommand command);
}
