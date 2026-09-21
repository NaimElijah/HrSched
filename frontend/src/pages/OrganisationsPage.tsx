import { useState } from 'react';
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
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const organisations = useAsync(() => access.findOrganisations(), sessionKey);

  async function onboard(event: React.FormEvent) {
    event.preventDefault();
    setFailure(null);
    setDone(null);
    try {
      const onboarded = await access.onboardOrganisation(name);
      setName('');
      setDone(`Onboarded ${onboarded.name}.`);
      organisations.reload();
    } catch (error) {
      setFailure(error);
    }
  }

  return (
    <section>
      <h2>Organisations</h2>
      <p className="hint">
        The platform operator&apos;s screen: the register of tenants, and nothing inside one. In any
        other role, listing and onboarding are refused.
      </p>

      <form className="row" onSubmit={(event) => void onboard(event)}>
        <label>
          Name
          <input value={name} onChange={(event) => setName(event.target.value)} required />
        </label>
        <button type="submit">Onboard organisation</button>
      </form>

      <Failure error={failure} />
      <p className="done" role="status">
        {done}
      </p>
      <Failure error={organisations.error} />
      <Busy busy={organisations.busy} />

      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Identifier</th>
          </tr>
        </thead>
        <tbody>
          {(organisations.value ?? []).map((organisation) => (
            <tr key={organisation.id}>
              <td>{organisation.name}</td>
              <td>
                <code>{organisation.id}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
