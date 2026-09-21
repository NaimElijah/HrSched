import { useState } from 'react';
import { accounts } from '../api/access';
import type { Account, TenantRole } from '../api/access';
import { useSessionKey } from '../auth/SessionProvider';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';

const ROLES: readonly TenantRole[] = ['EMPLOYEE', 'SCHEDULER', 'ADMIN'];

/**
 * Admin: who may act inside this organisation (requirement F15).
 *
 * **Nothing on this screen names an organisation.** The backend takes it from the token, so there
 * is no field for one and nothing an Admin could type to reach somebody else's staff.
 *
 * Every rule belongs to the backend and its refusals are rendered as themselves: the last Admin
 * cannot be demoted or disabled, a sign-in name is unique across the platform, and an employee link
 * must name somebody this organisation employs. This screen does not pre-empt any of them, because
 * a form that looked like it enforced a rule would be no evidence the rule holds.
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
  const [employee, setEmployee] = useState('');
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const listed = useAsync(() => accounts.findAccounts(), sessionKey);

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
    const created = await administer(`Created ${signInName}.`, () =>
      accounts.createAccount({
        signInName,
        temporaryPassword,
        role,
        ...(employee === '' ? {} : { employee }),
      }),
    );
    if (created) {
      setSignInName('');
      setTemporaryPassword('');
      setEmployee('');
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

      <form className="row" onSubmit={(event) => void create(event)}>
        <label>
          Sign-in name
          <input
            value={signInName}
            onChange={(event) => setSignInName(event.target.value)}
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
            required
          />
        </label>
        <label>
          Employee identifier (optional)
          <input value={employee} onChange={(event) => setEmployee(event.target.value)} />
        </label>
        <button type="submit">Create account</button>
      </form>

      <Failure error={failure} />
      <p className="done" role="status">
        {done}
      </p>
      <Failure error={listed.error} />
      <Busy busy={listed.busy} />

      <table>
        <thead>
          <tr>
            <th>Sign-in name</th>
            <th>Role</th>
            <th>State</th>
            <th>Reset password</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {(listed.value ?? []).map((account) => (
            <AccountRow key={account.id} account={account} administer={administer} />
          ))}
        </tbody>
      </table>
    </section>
  );
}

/**
 * One account, with the three things that can be done to it.
 *
 * A row of its own because the password field is per account: one shared field would let somebody
 * type a password for a colleague and click reset on the wrong line.
 */
function AccountRow({
  account,
  administer,
}: {
  account: Account;
  administer: (what: string, change: () => Promise<void>) => Promise<boolean>;
}) {
  const [replacement, setReplacement] = useState('');

  async function reset() {
    if (replacement === '') {
      return;
    }
    const done = await administer(`Reset the password of ${account.signInName}.`, () =>
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
        <label>
          <span className="visually-hidden">Role for {account.signInName}</span>
          <select
            value={account.role}
            onChange={(event) =>
              void administer(`Changed the role of ${account.signInName}.`, () =>
                accounts.changeMembership(
                  account.id,
                  event.target.value as TenantRole,
                  account.employee,
                ),
              )
            }
          >
            {ROLES.map((candidate) => (
              <option key={candidate} value={candidate}>
                {candidate}
              </option>
            ))}
          </select>
        </label>
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
          />
        </label>
        <button type="button" onClick={() => void reset()}>
          Reset password
        </button>
      </td>
      <td>
        <button
          type="button"
          onClick={() =>
            void administer(
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
