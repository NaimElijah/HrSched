import { useId, type RefObject } from 'react';
import { scheduling } from '../api/scheduling';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { RULE_FIELDS } from '../components/ruleFields';

/**
 * The rules a published roster was judged against: the version it names, read back as it was
 * then, however the rules have changed since (Q9).
 *
 * Read-only, and it shows only what was stored. A threshold missing here is a rule that version
 * did not enforce, and nothing is put in its place.
 *
 * It is opened from the rosters list, which moves focus to its heading and gives it back to the
 * button that opened it on Close.
 */
export function ApprovingRules({
  id,
  headingRef,
  version,
  onClose,
}: {
  /** The region's id, which the button that opened it names as what it controls. */
  id: string;
  /** The heading, focusable, for the opener to move focus to. */
  headingRef: RefObject<HTMLHeadingElement | null>;
  /** The constraint version the roster was approved under, read back as it stood then. */
  version: number;
  /** Called by Close; the opener removes the rules and decides where focus goes next. */
  onClose: () => void;
}) {
  const sessionKey = useSessionKey();
  const heading = useId();
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
    <section id={id} aria-labelledby={heading}>
      <h3 id={heading} ref={headingRef} tabIndex={-1}>
        Rules of version {version}
      </h3>
      <button
        type="button"
        aria-label={`Close rules of version ${String(version)}`}
        onClick={onClose}
      >
        Close
      </button>
      <Failure error={rules.error} />
      <Busy busy={rules.busy} />
      {/* Whether anything was enforced is the server's list to say, not this page's labels: a rule
          added to the API before it gets a label here is still named, never reported as absent. */}
      {loaded === null ? null : loaded.constraints.length === 0 ? (
        <p>No working-time rule was enforced under this version.</p>
      ) : (
        <>
          <ul>
            {loaded.constraints.map((constraint) => (
              <li key={constraint}>{constraint}</li>
            ))}
          </ul>
          {enforced.length === 0 ? null : (
            <dl>
              {enforced.map(({ key, label, value }) => (
                <div key={key}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
          )}
        </>
      )}
    </section>
  );
}
