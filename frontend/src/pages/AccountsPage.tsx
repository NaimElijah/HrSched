import { useLayoutEffect, useRef, useState } from 'react';
import { accounts } from '../api/access';
import type { Account, TenantRole } from '../api/access';
import { workforce } from '../api/workforce';
import type { Employee } from '../api/workforce';
import { useSessionKey } from '../auth/SessionProvider';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';

const ROLES: readonly TenantRole[] = ['EMPLOYEE', 'SCHEDULER', 'ADMIN'];

/** The value of the employee choice that links an account to nobody. */
const NOT_LINKED = '';

/**
 * Admin: who may act inside this organisation (requirement F15).
 *
 * **Nothing on this screen names an organisation.** The backend takes it from the token, so there
 * is no field for one and nothing an Admin could type to reach somebody else's staff.
 *
 * Every rule belongs to the backend and its refusals are rendered as themselves: the last Admin
 * cannot be demoted or disabled, a sign-in name is unique across the platform, an employee link
 * must name somebody this organisation employs, and an Employee account is always linked to
 * somebody (R34) — one stored unlinked before that rule is restored only once it is linked. This
 * screen does not pre-empt any of them, because a form that looked like it enforced a rule would
 * be no evidence the rule holds.
 *
 * A password is typed here and never comes back. Nothing is defaulted, every field is masked, and
 * each is cleared once the request it was typed for has succeeded — kept on failure, so that a
 * refused attempt is retyped only when there is something to change.
 */
export function AccountsPage() {
  const sessionKey = useSessionKey();
  const [signInName, setSignInName] = useState('');
  const [role, setRole] = useState<TenantRole>('EMPLOYEE');
  const [temporaryPassword, setTemporaryPassword] = useState('');
  const [employee, setEmployee] = useState(NOT_LINKED);
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);
  // A second creation sent while the first is on its way would be refused for the name the first
  // took, and that refusal shown beside the first one's success.
  const [creating, setCreating] = useState(false);

  const listed = useAsync(() => accounts.findAccounts(), sessionKey);
  const staff = useAsync(() => workforce.findEmployees(), sessionKey);

  /** Runs an administrative request, then reloads: every one of them changes what the table says. */
  async function administer(what: string, change: () => Promise<void>) {
    setFailure(null);
    setDone(null);
    try {
      await change();
      setDone(what);
      listed.reload();
      return true;
    } catch (error) {
      setFailure(error);
      return false;
    }
  }

  async function create(event: React.FormEvent) {
    event.preventDefault();
    if (creating) {
      return;
    }
    setCreating(true);
    const created = await administer(`Created ${signInName}.`, () =>
      accounts.createAccount({
        signInName,
        temporaryPassword,
        role,
        ...(employee === NOT_LINKED ? {} : { employee }),
      }),
    );
    setCreating(false);
    if (created) {
      setSignInName('');
      setTemporaryPassword('');
      setEmployee(NOT_LINKED);
    }
  }

  return (
    <section>
      <h2>Accounts</h2>
      <p className="hint">
        Who may sign in to this organisation, and what they may do. Everybody created here is given
        a temporary password and made to choose their own the first time they sign in. Nobody can
        create their own account.
      </p>

      {/* Every credential here is a colleague's, never the Admin's own. new-password keeps the
          browser from autofilling the Admin's saved password into a colleague's account or offering
          it as an update to the Admin's own entry; off asks it not to autofill the name. A browser
          may still offer to save what is typed, under the colleague's name: these hints do not
          promise more (#250). */}
      <form className="row" onSubmit={(event) => void create(event)}>
        <label>
          Sign-in name
          <input
            value={signInName}
            onChange={(event) => setSignInName(event.target.value)}
            autoComplete="off"
            required
          />
        </label>
        <label>
          Role
          <select value={role} onChange={(event) => setRole(event.target.value as TenantRole)}>
            {ROLES.map((candidate) => (
              <option key={candidate} value={candidate}>
                {candidate}
              </option>
            ))}
          </select>
        </label>
        <label>
          Temporary password
          <input
            type="password"
            value={temporaryPassword}
            onChange={(event) => setTemporaryPassword(event.target.value)}
            autoComplete="new-password"
            required
          />
        </label>
        <label>
          Employee
          <select value={employee} onChange={(event) => setEmployee(event.target.value)}>
            <EmployeeOptions staff={staff.value ?? []} linked={null} />
          </select>
        </label>
        <button type="submit" disabled={creating}>
          Create account
        </button>
      </form>

      <Failure error={failure} />
      <p className="done" role="status">
        {done}
      </p>
      <Failure error={listed.error} />
      <Failure error={staff.error} />
      <Busy busy={listed.busy} />

      <table>
        <thead>
          <tr>
            <th>Sign-in name</th>
            <th>Role</th>
            <th>Employee</th>
            <th>State</th>
            <th>Reset password</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {(listed.value ?? []).map((account) => (
            <AccountRow
              key={account.id}
              account={account}
              staff={staff.value ?? []}
              administer={administer}
            />
          ))}
        </tbody>
      </table>
    </section>
  );
}

/**
 * The choices for an employee link: nobody, or one of this organisation's employees.
 *
 * Each is shown by name and identifier together, because two people can share a name and linking
 * the wrong one hands that person's self-service to somebody else. A link to somebody the list
 * does not hold is offered by its identifier alone, so that it never reads as "Not linked".
 */
function EmployeeOptions({ staff, linked }: { staff: readonly Employee[]; linked: string | null }) {
  const unlisted = linked !== null && !staff.some((person) => person.id === linked);
  return (
    <>
      <option value={NOT_LINKED}>Not linked</option>
      {staff.map((person) => (
        <option key={person.id} value={person.id}>
          {person.name} · {person.id}
        </option>
      ))}
      {unlisted ? <option value={linked}>{linked}</option> : null}
    </>
  );
}

/**
 * One account, with the four things that can be done to it.
 *
 * A row of its own because the password field is per account: one shared field would let somebody
 * type a password for a colleague and click reset on the wrong line.
 *
 * A role or an employee is chosen, then submitted. A change sent on choosing would be made by an
 * arrow key, since a closed select changes on every one in some browsers, and an Admin could demote
 * themselves in one keystroke. Change sends nothing until something other than what is stored is
 * chosen: the backend stores and audits a membership change whether or not it differs.
 *
 * Every change made here is sent from the copy of the account the row was drawn from, and the
 * membership is one value, so a change resends the field it leaves alone. Sent from a copy read
 * before the last change was stored, the next change would put back what that one replaced. So the
 * row waits while its change is on its way, and then until the read-back that change started has
 * replaced the copy the row held when it was answered. A copy another row's read-back brings in the
 * meantime does not end the wait. A row remounted while its change is on its way, by a new session
 * or a list that no longer holds the account, forgets that it was waiting: that is not covered.
 */
function AccountRow({
  account,
  staff,
  administer,
}: {
  account: Account;
  staff: readonly Employee[];
  administer: (what: string, change: () => Promise<void>) => Promise<boolean>;
}) {
  const [replacement, setReplacement] = useState('');
  // What has been chosen and not yet stored, with the stored value it was chosen against. A choice
  // outlives a read-back that leaves that value alone, such as another row's, and is dropped once
  // the stored value changes: by this row's own change, or by somebody else's. A refused change
  // stores nothing, so its choice stays to be corrected.
  const [chosenRole, setChosenRole] = useState<{ against: TenantRole; role: TenantRole } | null>(
    null,
  );
  const [chosenEmployee, setChosenEmployee] = useState<{
    against: string | null;
    employee: string | null;
  } | null>(null);
  const role = chosenRole?.against === account.role ? chosenRole.role : account.role;
  const employee =
    chosenEmployee !== null && chosenEmployee.against === account.employee
      ? chosenEmployee.employee
      : account.employee;
  const roleChosen = role !== account.role;
  const employeeChosen = employee !== account.employee;
  // Whether this row's change is on its way, and then the copy of the account that change's own
  // read-back will replace. The copy is read from `held` when the change is answered, because by
  // then another row's read-back may have replaced the one the change was sent from.
  const [sending, setSending] = useState(false);
  const [readingBack, setReadingBack] = useState<Account | null>(null);
  const held = useRef(account);
  useLayoutEffect(() => {
    held.current = account;
  });
  const waiting = sending || readingBack === account;

  /** Sends one change, unless this row's last one is on its way or not yet read back. */
  async function change(what: string, request: () => Promise<void>): Promise<boolean> {
    if (waiting) {
      return false;
    }
    setSending(true);
    const changed = await administer(what, request);
    setSending(false);
    // A refusal reads nothing back, so there is no new copy to wait for.
    setReadingBack(changed ? held.current : null);
    return changed;
  }

  async function reset() {
    if (replacement === '') {
      return;
    }
    const done = await change(`Reset the password of ${account.signInName}.`, () =>
      accounts.resetPassword(account.id, replacement),
    );
    if (done) {
      setReplacement('');
    }
  }

  return (
    <tr>
      <td>{account.signInName}</td>
      <td>
        <form
          className="row"
          onSubmit={(event) => {
            event.preventDefault();
            if (!roleChosen) {
              return;
            }
            void change(`Changed the role of ${account.signInName}.`, () =>
              accounts.changeMembership(account.id, role, account.employee),
            );
          }}
        >
          <label>
            <span className="visually-hidden">Role for {account.signInName}</span>
            <select
              value={role}
              disabled={waiting}
              onChange={(event) =>
                setChosenRole({ against: account.role, role: event.target.value as TenantRole })
              }
            >
              {ROLES.map((candidate) => (
                <option key={candidate} value={candidate}>
                  {candidate}
                </option>
              ))}
            </select>
          </label>
          <button
            type="submit"
            disabled={waiting || !roleChosen}
            aria-label={`Change the role of ${account.signInName}`}
          >
            Change
          </button>
        </form>
      </td>
      <td>
        <form
          className="row"
          onSubmit={(event) => {
            event.preventDefault();
            if (!employeeChosen) {
              return;
            }
            void change(`Changed who ${account.signInName} is.`, () =>
              accounts.changeMembership(account.id, account.role, employee),
            );
          }}
        >
          <label>
            <span className="visually-hidden">Employee for {account.signInName}</span>
            <select
              value={employee ?? NOT_LINKED}
              disabled={waiting}
              onChange={(event) =>
                setChosenEmployee({
                  against: account.employee,
                  employee: event.target.value === NOT_LINKED ? null : event.target.value,
                })
              }
            >
              <EmployeeOptions staff={staff} linked={account.employee} />
            </select>
          </label>
          <button
            type="submit"
            disabled={waiting || !employeeChosen}
            aria-label={`Change the employee of ${account.signInName}`}
          >
            Change
          </button>
        </form>
      </td>
      <td>
        {account.active ? 'Active' : 'Disabled'}
        {account.locked ? ' · Locked' : ''}
        {account.passwordChangeRequired ? ' · Password change pending' : ''}
      </td>
      <td>
        <label>
          <span className="visually-hidden">New temporary password for {account.signInName}</span>
          <input
            type="password"
            value={replacement}
            onChange={(event) => setReplacement(event.target.value)}
            autoComplete="new-password"
          />
        </label>
        <button type="button" disabled={waiting} onClick={() => void reset()}>
          Reset password
        </button>
      </td>
      <td>
        <button
          type="button"
          disabled={waiting}
          onClick={() =>
            void change(
              account.active
                ? `Disabled ${account.signInName}.`
                : `Restored ${account.signInName}.`,
              () => accounts.setAccountActive(account.id, !account.active),
            )
          }
        >
          {account.active ? 'Disable' : 'Restore'}
        </button>
      </td>
    </tr>
  );
}
