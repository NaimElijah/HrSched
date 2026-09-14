import { useState } from 'react';
import { access } from '../api/access';
import { identityKey } from '../api/client';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useActingAs } from '../identity/ActingAs';

/**
 * Operator: the organisations on the platform (F13) — list them, onboard one, read the one being
 * acted in, and switch the acting identity into another.
 *
 * Every request goes out as the identity the bar above claims, unchanged. A tenant role is refused
 * the listing and onboarding, and this page shows that refusal: which role administers the platform
 * is the backend's rule (ADR-019), not something to decide by hiding a tab.
 *
 * Switching organisation clears the employee link, because a personnel record belongs to one
 * organisation and would otherwise be claimed inside another.
 */
export function OrganisationsPage() {
  const { identity, setOrganisation } = useActingAs();
  const [name, setName] = useState('');
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);

  const organisations = useAsync(() => access.findOrganisations(identity), identityKey(identity));
  const current = useAsync(
    () => access.findOrganisation(identity, identity.organisation),
    identityKey(identity),
  );

  async function onboard(event: React.FormEvent) {
    event.preventDefault();
    setFailure(null);
    setDone(null);
    try {
      const onboarded = await access.onboardOrganisation(identity, name);
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
        The platform operator&apos;s screen. Every request here is made as the identity above, so in
        any other role listing and onboarding are refused.
      </p>

      <Failure error={current.error} />
      {current.value === null ? null : <p>{`You are acting in ${current.value.name}.`}</p>}

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
            <th>
              <span className="visually-hidden">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {(organisations.value ?? []).map((organisation) => (
            <tr key={organisation.id}>
              <td>{organisation.name}</td>
              <td>
                <code>{organisation.id}</code>
              </td>
              <td>
                {organisation.id === identity.organisation ? (
                  'Acting in this one'
                ) : (
                  <button type="button" onClick={() => setOrganisation(organisation.id)}>
                    {`Act in ${organisation.name}`}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
