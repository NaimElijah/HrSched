package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.EmployeeAlreadyLinkedException;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInNameTakenException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: storing the accounts that may sign in.
 *
 * <p>Reads are scoped to an organisation, as everywhere else (ADR-009), with the two named
 * exceptions below; ADR-055 lists every read in access that takes no organisation, and why. An
 * account is personal data about a colleague — who they are, what they may do, and whether somebody
 * has been guessing their password — so a read that forgot its tenant predicate would hand one
 * customer another's staff list.
 *
 * <p>The platform operator belongs to no organisation and is therefore invisible to every scoped
 * read here. That is the intended behaviour rather than a gap: an operator is not a member of any
 * tenant's staff, and must not appear in one's administration.
 */
public interface AccountRepository {

  /**
   * Stores an account, inserting it or replacing the stored version of it.
   *
   * @param account the account to store, never {@code null}
   * @throws SignInNameTakenException if another account already signs in under that name
   * @throws EmployeeAlreadyLinkedException if another account in the same organisation is already
   *     linked to that employee
   * @throws IllegalStateException if another organisation holds an account with that identity, or
   *     the operator does while this one belongs to an organisation, or the other way round: a save
   *     never moves an account between organisations, nor turns it into or out of the operator
   */
  void save(Account account);

  /**
   * Stores an account only if no account already signs in under its name.
   *
   * <p>Asking and writing are one statement rather than a read followed by a write, so two
   * instances starting at the same moment cannot both decide the name was free.
   *
   * @param account the account to store, never {@code null}
   * @return whether this call stored it; false means another account already holds that sign-in
   *     name, and nothing was changed - in particular, no password was overwritten
   */
  boolean saveIfAbsent(Account account);

  /**
   * @param organisation the organisation whose account this must be
   * @param account which account to read
   * @return the account, or empty if it belongs to another organisation or does not exist — the two
   *     are deliberately indistinguishable, so a caller cannot learn that an account exists
   *     elsewhere
   */
  Optional<Account> find(OrganisationId organisation, UserId account);

  /**
   * @param organisation whose accounts to list
   * @return every account of that organisation, oldest first and ties broken by identity, so two
   *     calls return the same order
   */
  List<Account> findAll(OrganisationId organisation);

  /**
   * Finds an account by the name it signs in with, across every organisation.
   *
   * <p><strong>The first of the two unscoped reads in this port,</strong> and it has to be: a
   * sign-in request carries a name and a password and nothing else, so there is no organisation to
   * scope by until the account has been found. Sign-in names are unique platform-wide precisely so
   * that this question has one answer.
   *
   * <p>Safe only because of what each caller does with it, and there are two. Whoever signs in is
   * then the account that was found, so no caller can reach another tenant's data through it. The
   * operator bootstrap is the other: it runs at startup with no actor and no request, asks only
   * after its own insert was refused, and reads only whether the account in the way is the operator
   * - nothing of it is returned to anybody, and its identity alone reaches the log. Do not reuse
   * this to implement a lookup on behalf of somebody who already has an organisation — use {@link
   * #find(OrganisationId, UserId)}.
   *
   * @param name the sign-in name submitted
   * @return the account signing in under that name, or empty if there is none
   */
  Optional<Account> findBySignInName(SignInName name);

  /**
   * Finds an account by identity alone, across every organisation.
   *
   * <p><strong>The second and last unscoped read in this port,</strong> and named so that its
   * absence of a tenant predicate is a decision somebody made rather than one somebody forgot.
   * Authentication has to work before there is an organisation to scope by: a token names an
   * account, and the operator's account belongs to no organisation at all, so a scoped lookup could
   * not find it under any argument.
   *
   * <p>Safe only because of what the caller does with it. What comes back is used to decide who the
   * caller <em>is</em>, never to answer a question they asked about somebody else. Anything acting
   * on an account on behalf of a user belongs on {@link #find(OrganisationId, UserId)}.
   *
   * @param account whose account to read
   * @return the account, or empty if there is none
   */
  Optional<Account> findForAuthentication(UserId account);

  /**
   * Reads an account and holds it until the calling use case finishes, so another use case reading
   * it the same way waits, and then reads what this one stored.
   *
   * <p>For a use case that changes the account it reads. An account is stored whole, so a change
   * made to a copy read before another committed would store over it: a failed sign-in counted on a
   * copy read before an Admin disabled the account would enable it again (#33, ADR-041).
   *
   * <p>Scoped as {@link #find(OrganisationId, UserId)} is, except that an empty organisation names
   * the platform operator, whose account belongs to none. A tenant's account is never found under
   * the operator's empty organisation, nor the operator's under any organisation.
   *
   * @param organisation the organisation the account must belong to, or empty for the operator's
   * @param account which account to read and hold
   * @return the account, or empty if there is no such account where it was looked for
   * @throws IllegalStateException if called outside a writable unit of work, where there is nothing
   *     to hold the account until
   */
  Optional<Account> findForUpdate(Optional<OrganisationId> organisation, UserId account);

  /**
   * Takes an organisation's account administration, so that two administrators changing accounts at
   * once take turns rather than both deciding against the same stored state.
   *
   * <p>Needed because the rule that an organisation keeps at least one active Admin is checked by
   * reading before writing: two concurrent requests, each disabling a different one of the last two
   * Admins, would each read an organisation that still had two and leave it with none.
   *
   * <p>The lock lasts for the calling transaction and is released with it, so no path can forget to
   * let it go. It is keyed by organisation, so two customers never wait on each other.
   *
   * @param organisation whose account administration to take
   * @throws IllegalStateException if called outside a transaction, where the lock would be released
   *     as soon as it was taken and nothing would actually take turns
   */
  void lockAdministrationOf(OrganisationId organisation);
}
