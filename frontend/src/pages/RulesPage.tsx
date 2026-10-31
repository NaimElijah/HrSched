import { useId, useRef, useState } from 'react';
import { scheduling } from '../api/scheduling';
import type { ConstraintSet, ConstraintSetRequest } from '../api/scheduling';
import { Busy, Failure } from '../components/Feedback';
import { useAsync } from '../components/useAsync';
import { useSessionKey } from '../auth/SessionProvider';
import { RULE_FIELDS, type RuleField } from '../components/ruleFields';

function configured(set: ConstraintSet | null, key: RuleField): string {
  const value = set === null ? null : set[key];
  return value === null || value === undefined ? '' : String(value);
}

/**
 * Admin: this organisation's working-time rules.
 *
 * Every field is blank unless the organisation has configured that rule, and no value is ever
 * suggested. HrSched hardcodes no jurisdiction, and a number offered by this form would be a legal
 * threshold chosen by a developer. A jurisdiction is a preset somebody loads, with its source cited.
 *
 * Each field shows the configured value until it is edited, and that is load-bearing rather than a
 * convenience: revising replaces the whole set, so submitting a form that had only one field filled
 * in would delete every other rule. Clearing a field is how a rule is switched off, and it must be
 * the only way. For the same reason nothing is sent until the configured rules have loaded: until
 * then a blank field says nothing about what is configured.
 */
export function RulesPage() {
  // Only what the admin has actually typed. Everything else is derived from the loaded rules, so
  // no effect syncs two copies of the same state — and an empty string is a real edit meaning
  // "switch this rule off", which is why it is distinct from an absent key.
  const [edits, setEdits] = useState<Partial<Record<RuleField, string>>>({});
  const [failure, setFailure] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);
  // A second revision sent meanwhile would be made from a copy the first has already replaced.
  const [revising, setRevising] = useState(false);
  // What the next read to settle answers: a stored revision's read-back, until which the edits
  // stay in the fields, or a retried load, which says so once it has loaded.
  const [awaiting, setAwaiting] = useState<'read-back' | 'retry' | null>(null);
  const sessionKey = useSessionKey();
  const waitingId = useId();
  const heading = useRef<HTMLHeadingElement>(null);

  const current = useAsync(() => scheduling.currentConstraints(), sessionKey);
  const loaded = current.value;
  // Until the read-back lands the form holds the copy the revision replaced, and a revision sent
  // from it would put the old values back.
  const unavailable = loaded === null || revising || current.busy;

  if (awaiting !== null && !current.busy) {
    // Adjusted while rendering rather than in an effect, so no frame shows the replaced values
    // beside the new version: the pattern React documents as storing information from previous
    // renders, under useState.
    setAwaiting(null);
    if (awaiting === 'read-back') {
      setEdits({});
    } else if (current.error === null) {
      setDone('Stored rules loaded.');
    }
  }

  const shown = (key: RuleField): string => edits[key] ?? configured(loaded, key);

  async function revise(event: React.FormEvent) {
    event.preventDefault();
    // Until the stored rules are known every field reads "not enforced" while nothing is, and a
    // revision sent then would switch off every rule that was not retyped (#239).
    if (unavailable) {
      return;
    }
    setFailure(null);
    setDone(null);
    const number = (key: RuleField): number | undefined => {
      const value = shown(key).trim();
      return value === '' ? undefined : Number(value);
    };
    const body: ConstraintSetRequest = {
      minimumRestMinutes: number('minimumRestMinutes'),
      maximumDailyMinutes: number('maximumDailyMinutes'),
      maximumWeeklyMinutes: number('maximumWeeklyMinutes'),
      maximumConsecutiveDays: number('maximumConsecutiveDays'),
    };
    setRevising(true);
    try {
      const revised = await scheduling.reviseConstraints(body);
      // The fields look the same before and after, so without a word nothing says it was stored.
      setDone(`Rules revised: now version ${String(revised.version)}.`);
      setAwaiting('read-back');
      current.reload();
    } catch (error) {
      setFailure(error);
    } finally {
      setRevising(false);
    }
  }

  return (
    <section>
      <h2 ref={heading} tabIndex={-1}>
        Working-time rules
      </h2>
      <p className="hint">
        Blank means a rule is not enforced, and clearing a field is how you switch one off. No value
        is suggested here on purpose — HrSched encodes no country&apos;s law, and every threshold is
        configuration.
      </p>

      <p>
        Current version: <strong>{loaded?.version ?? '—'}</strong>
      </p>
      <ul>
        {(loaded?.constraints ?? []).map((constraint) => (
          <li key={constraint}>{constraint}</li>
        ))}
      </ul>

      {/* Change events bubble, so any edit clears a success that would read as the next one's. */}
      <form onSubmit={revise} onChange={() => setDone(null)} className="grid">
        {RULE_FIELDS.map(([key, label]) => (
          <label key={key}>
            {label}
            <input
              type="number"
              min="1"
              value={shown(key)}
              placeholder="not enforced"
              onChange={(event) => setEdits({ ...edits, [key]: event.target.value })}
            />
          </label>
        ))}
        {/* aria-disabled rather than disabled, so the button keeps focus and can say why. */}
        <button
          type="submit"
          aria-disabled={unavailable}
          aria-describedby={loaded === null ? waitingId : undefined}
        >
          Revise rules
        </button>
      </form>
      {loaded === null ? (
        <p id={waitingId} className="hint">
          {current.error === null
            ? 'Revising waits until the stored rules have loaded.'
            : 'The stored rules did not load, so there is nothing to revise from yet.'}
        </p>
      ) : null}
      <Failure error={current.error} />
      {current.error === null ? null : (
        <button
          type="button"
          onClick={() => {
            // The button goes with the failure it answers, so focus moves on before it does.
            heading.current?.focus();
            setAwaiting('retry');
            current.reload();
          }}
        >
          Try again
        </button>
      )}

      <Failure error={failure} />
      <p className="done" role="status">
        {done}
      </p>
      <Busy busy={current.busy} />
    </section>
  );
}
