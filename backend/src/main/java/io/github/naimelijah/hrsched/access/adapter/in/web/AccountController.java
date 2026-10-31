package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.access.application.port.in.ChangeMembershipCommand;
import io.github.naimelijah.hrsched.access.application.port.in.CreateAccountCommand;
import io.github.naimelijah.hrsched.access.application.port.in.ManageAccountsUseCase;
import io.github.naimelijah.hrsched.access.application.port.in.PublishedAccount;
import io.github.naimelijah.hrsched.access.application.port.in.ResetPasswordCommand;
import io.github.naimelijah.hrsched.access.application.port.in.SetAccountActiveCommand;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Driving adapter exposing account administration over HTTP (requirement F15).
 *
 * <p><strong>The organisation comes from the acting account, never from the request</strong>, as in
 * every controller that acts inside an organisation (ADR-031). Here the stakes are highest: an
 * Admin who could name the organisation they were administering could create accounts in somebody
 * else's. The acting account belongs to exactly one organisation and these routes act in that one,
 * so there is no identifier in any path or body for a caller to change.
 *
 * <p>That also makes the platform operator's refusal structural rather than a check: an operator
 * belongs to no organisation, so there is nothing for these routes to act in. The chain's Admin
 * rule refuses it first, and {@link Actor#requireTenantMembership} would refuse it before any use
 * case ran (ADR-030).
 *
 * <p>A password given to somebody never comes back out. Neither the creation response nor any read
 * carries one, and the requests that contain one are the only place it exists.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

  private final ManageAccountsUseCase manageAccounts;

  /**
   * @param manageAccounts the use case that administers an organisation's accounts
   */
  public AccountController(ManageAccountsUseCase manageAccounts) {
    this.manageAccounts = manageAccounts;
  }

  /**
   * Creates an account in the acting Admin's own organisation.
   *
   * @param actor the Admin, taken from their verified token
   * @param request who to create; it names no organisation
   * @return 201 with a {@code Location} naming the account and no body. The password is never
   *     echoed
   */
  @PostMapping
  public ResponseEntity<Void> create(
      @AuthenticationPrincipal Actor actor, @RequestBody CreateAccountRequest request) {
    UserId created =
        manageAccounts.createAccount(
            actor,
            new CreateAccountCommand(
                organisationOf(actor),
                request.requiredSignInName(),
                request.requiredTemporaryPassword(),
                request.requiredRole(),
                request.linkedEmployee()));
    // Created, and nothing about it echoed. The body would have to carry either the password that
    // was sent or the sign-in name that identifies somebody, and a Location is enough.
    return ResponseEntity.created(URI.create("/api/accounts/" + created.value())).build();
  }

  /**
   * Lists the accounts of the acting Admin's own organisation, oldest first.
   *
   * @param actor the Admin, taken from their verified token
   * @return 200 with every account of that organisation, the platform operator never among them
   */
  @GetMapping
  public List<AccountResponse> list(@AuthenticationPrincipal Actor actor) {
    return manageAccounts.findAccounts(actor, organisationOf(actor)).stream()
        .map(AccountController::respond)
        .toList();
  }

  /**
   * The account a creation's {@code Location} names. One the acting Admin's organisation does not
   * hold, another's included, is the same 404 as one that does not exist.
   *
   * @param actor the Admin, taken from their verified token
   * @param id the account to read
   * @return 200 with the account
   */
  @GetMapping("/{id}")
  public AccountResponse find(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    return respond(manageAccounts.findAccount(actor, organisationOf(actor), new UserId(id)));
  }

  /**
   * Changes an account's role and the employee it is, together.
   *
   * @param actor the Admin, taken from their verified token
   * @param id the account to change
   * @param request the role it takes, and the employee it is; an absent employee unlinks it
   * @return 204. The change applies to the account's next request, without ending its sessions
   */
  @PutMapping("/{id}/membership")
  public ResponseEntity<Void> changeMembership(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @RequestBody ChangeMembershipRequest request) {
    manageAccounts.changeMembership(
        actor,
        new ChangeMembershipCommand(
            organisationOf(actor),
            new UserId(id),
            request.requiredRole(),
            request.linkedEmployee()));
    return ResponseEntity.noContent().build();
  }

  /**
   * Disables an account, or restores one. Disabling ends every session the account holds.
   *
   * @param actor the Admin, taken from their verified token
   * @param id the account to change
   * @param request whether the account may act
   * @return 204
   */
  @PutMapping("/{id}/activation")
  public ResponseEntity<Void> setActive(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @RequestBody SetActiveRequest request) {
    manageAccounts.setAccountActive(
        actor,
        new SetAccountActiveCommand(
            organisationOf(actor), new UserId(id), request.requiredActive()));
    return ResponseEntity.noContent().build();
  }

  /**
   * Gives somebody else's account a new temporary password, clearing any lock on it and ending
   * every session it holds. An Admin's own account is refused: that takes the current password, at
   * {@code /api/auth/password}.
   *
   * @param actor the Admin, taken from their verified token
   * @param id the account to reset
   * @param request the temporary password to give it
   * @return 204
   */
  @PostMapping("/{id}/password")
  public ResponseEntity<Void> resetPassword(
      @AuthenticationPrincipal Actor actor,
      @PathVariable UUID id,
      @RequestBody ResetPasswordRequest request) {
    manageAccounts.resetPassword(
        actor,
        new ResetPasswordCommand(
            organisationOf(actor), new UserId(id), request.requiredTemporaryPassword()));
    return ResponseEntity.noContent().build();
  }

  /**
   * The organisation these routes act in, taken from the acting account and from nowhere else.
   *
   * <p>An operator is refused by the kernel's own guard, for the same reason as everywhere else: it
   * belongs to no organisation, so there is no organisation for it to administer accounts in
   * (ADR-030).
   */
  private static OrganisationId organisationOf(Actor actor) {
    return actor.requireTenantMembership().organisation();
  }

  private static AccountResponse respond(PublishedAccount account) {
    return new AccountResponse(
        account.id().value(),
        account.signInName(),
        account.role(),
        account.employee().map(employee -> employee.value()).orElse(null),
        account.active(),
        account.passwordChangeRequired(),
        account.locked(),
        account.createdAt());
  }
}
