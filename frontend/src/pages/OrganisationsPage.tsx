import { useId, useState } from 'react';
import { access } from '../api/access';
import { useSessionKey } from '../auth/SessionProvider';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';

/**
 * Operator: the organisations on the platform (F13) — list them and onboard one.
 *
 * There is no longer a way to act inside one from here. The operator belongs to no organisation and
 * is refused everything inside every one of them (ADR-030); the button that used to switch an
 * "acting identity" only worked because the backend believed whatever headers it was sent.
 *
 * A tenant role is refused the listing and onboarding, and this page shows that refusal rather than
 * hiding itself: which identity administers the platform is the backend's rule (ADR-019), not
 * something to decide by hiding a tab.
 */
export function OrganisationsPage() {
  const sessionKey = useSessionKey();
  const [name, setName] = useState('');
  const [adminSignInName, setAdminSignInName] = useState('');
  const [temporaryPassword, setTemporaryPassword] = useState('');
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);
  // A second onboarding sent while the first is on its way would be refused for the name the first
  // took, and that refusal shown beside the first one's success.
  const [onboarding, setOnboarding] = useState(false);

  const captionId = useId();

  const organisations = useAsync(() => access.findOrganisations(), sessionKey);

  async function onboard(event: React.FormEvent) {
    event.preventDefault();
    if (onboarding) {
      return;
    }
    setFailure(null);
    setDone(null);
    setOnboarding(true);
    try {
      const onboarded = await access.onboardOrganisation(name, {
        signInName: adminSignInName,
        temporaryPassword,
      });
      setName('');
      setAdminSignInName('');
      // Cleared on success and kept on failure: a refused onboarding is retyped, and retyping a
      // password nobody can read is the one thing that would push somebody towards a weaker one.
      setTemporaryPassword('');
      setDone(`Onboarded ${onboarded.name}.`);
      organisations.reload();
    } catch (error) {
      setFailure(error);
    } finally {
      setOnboarding(false);
    }
  }

  return (
    <section>
      <h2>Organisations</h2>
      <p className="hint">
        The platform operator&apos;s screen: the register of tenants, and nothing inside one. In any
        other role, listing and onboarding are refused.
      </p>

      <p className="hint">
        An organisation is onboarded together with its first Admin, who is made to choose their own
        password the first time they sign in.
      </p>

      {/* Both credentials are the first Admin's, never the operator's. new-password keeps the
          browser from autofilling the operator's saved password into a new organisation or
          offering it as an update to the operator's own entry; off asks it not to autofill the
          name. A browser may still offer to save what is typed, under the new Admin's name (#250).
          Change events bubble, so any edit clears a success that would read as the next one's. */}
      <form
        className="row"
        onSubmit={(event) => void onboard(event)}
        onChange={() => setDone(null)}
      >
        <label>
          Name
          <input value={name} onChange={(event) => setName(event.target.value)} required />
        </label>
        <label>
          First admin sign-in name
          <input
            value={adminSignInName}
            onChange={(event) => setAdminSignInName(event.target.value)}
            autoComplete="off"
            required
          />
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
        {/* aria-disabled rather than disabled, so focus stays on the button while its request is
            on its way. */}
        <button type="submit" aria-disabled={onboarding}>
          Onboard organisation
        </button>
      </form>

      <Failure error={failure} />
      <p className="done" role="status">
        {done}
      </p>
      <Failure error={organisations.error} />
      <Busy busy={organisations.busy} />

      {/* Only an answer says which organisations there are: while loading, or after a refusal,
          there is no list to show, and an empty table would read as none. */}
      {organisations.value === null ? null : organisations.value.length === 0 ? (
        <p>No organisations yet.</p>
      ) : (
        <div className="table-scroll" role="region" aria-labelledby={captionId} tabIndex={0}>
          <table>
            <caption id={captionId} className="visually-hidden">
              Organisations on the platform
            </caption>
            <thead>
              <tr>
                <th>Name</th>
                <th>Identifier</th>
              </tr>
            </thead>
            <tbody>
              {organisations.value.map((organisation) => (
                <tr key={organisation.id}>
                  <td>{organisation.name}</td>
                  <td>
                    <code>{organisation.id}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
