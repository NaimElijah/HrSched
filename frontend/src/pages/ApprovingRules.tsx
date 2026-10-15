import { scheduling } from '../api/scheduling';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { RULE_FIELDS } from './ruleFields';

/**
 * The rules a published roster was judged against: the version it names, read back as it was
 * then, however the rules have changed since (Q9).
 *
 * Read-only, and it shows only what was stored. A threshold missing here is a rule that version
 * did not enforce, and nothing is put in its place.
 */
export function ApprovingRules({ version }: { version: number }) {
  const sessionKey = useSessionKey();
  const rules = useAsync(() => scheduling.constraintsAt(version), `${sessionKey}#${version}`);
  const loaded = rules.value;
  const enforced =
    loaded === null
      ? []
      : RULE_FIELDS.flatMap(([key, label]) => {
          const value = loaded[key];
          return value === null ? [] : [{ key, label, value }];
        });

  return (
    <section aria-label={`Rules of version ${String(version)}`}>
      <h3>Rules of version {version}</h3>
      <Failure error={rules.error} />
      <Busy busy={rules.busy} />
      {loaded === null ? null : enforced.length === 0 ? (
        <p>No working-time rule was enforced under this version.</p>
      ) : (
        <dl>
          {enforced.map(({ key, label, value }) => (
            <div key={key}>
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}
